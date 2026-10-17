package dev.adminos.api.domain.ingestion.fallback.evaluation

import dev.adminos.api.domain.ai.ALLOW_ALL_CONSENT
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiPrice
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ai.FakeAiProvider
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalKey
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.InMemoryMessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.MessageFallback
import dev.adminos.api.domain.ingestion.whatsapp.BankSenders
import dev.adminos.api.domain.ingestion.sms.SenderId
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.MessageProposal
import dev.adminos.api.domain.ingestion.fallback.ReadMessageTask
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessageSink
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.domain.ingestion.whatsapp.WhatsAppImportService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What a golden row should become. */
sealed interface Expected {
    data class Transaction(val direction: IngestDirection, val amount: BigDecimal, val last4: String?, val merchant: String?, val date: LocalDate?) : Expected
    data object NotATransaction : Expected
    /** The redactor must refuse to send it. */
    data object Withheld : Expected
    /** The regex rules drop it, or the sender isn't a bank: never offered to the fallback. */
    data object NotOffered : Expected
}

/** One synthetic message (`messages/golden.tsv`), its expected reading, and the recorded model answer (null: never asked). */
data class GoldenMessage(
    val id: String,
    val tags: Set<String>,
    val channel: String,
    val sender: String,
    val receivedAt: Instant,
    val text: String,
    val expected: Expected,
    val answer: String?
) {
    override fun toString() = "GoldenMessage($id, $tags)"
}

object MessageGoldenSet {
    const val RESOURCE = "/messages/golden.tsv"
    private val ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")

    fun load(): List<GoldenMessage> {
        val lines = (MessageGoldenSet::class.java.getResource(RESOURCE) ?: error("golden set not found")).readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        check(lines.first().startsWith("id\t")) { "golden set header missing" }
        return lines.drop(1).map { line ->
            val c = line.split('\t')
            check(c.size == 8) { "row has ${c.size} columns: ${c.firstOrNull()}" }
            GoldenMessage(
                id = c[0], tags = c[1].split(',').toSet(), channel = c[2], sender = c[3], receivedAt = Instant.parse(c[4]),
                text = ESCAPE.replace(c[5]) { it.groupValues[1].toInt(16).toChar().toString() },
                expected = expected(c[6]), answer = c[7].takeIf { it != "-" }
            )
        }
    }

    private fun expected(json: String): Expected {
        val o = Json.parseToJsonElement(json) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (s("kind")) {
            "transaction" -> Expected.Transaction(
                IngestDirection.valueOf(s("direction").orEmpty().uppercase()), BigDecimal(s("amount")), s("accountLast4"), s("merchant"), s("date")?.let(LocalDate::parse)
            )
            "not_a_transaction" -> Expected.NotATransaction
            "withheld" -> Expected.Withheld
            "not_offered" -> Expected.NotOffered
            else -> error("unknown expected kind in $json")
        }
    }
}

enum class Outcome { NOT_OFFERED, WITHHELD, ACCEPTED, SAID_NOT, REJECTED }

/** What the shipped path made of one golden row, and exactly what it sent to the model. */
data class Graded(val row: GoldenMessage, val outcome: Outcome, val proposal: MessageProposal?, val code: String?, val sent: String?)

/**
 * Measures the **fallback itself** (AOS-705, D-130): each golden row is offered to [MessageFallback] as the
 * unrecognised bank message it stands for → the fallback's offer (money word, redactor, dedup) → the gateway with a
 * provider that replays the recorded answer → the task's validator → Review. Only the provider is fake.
 *
 * The rows marked `not_offered` (OTPs, declines, promotions, people, reminders) go through the shipped intake
 * instead, which must never offer them. Which other messages reach the fallback at all is the regex rules' call, and
 * the rules keep improving, so that is checked separately, against the rules of the day, by `MessageRoutingTest`.
 */
object MessageEvaluation {
    val ZONE: ZoneId = UserCalendar.DEFAULT_ZONE
    private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)
    private val WHATSAPP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy, HH:mm")

    fun run(provider: AiProviderKind = AiProviderKind.OLLAMA, model: String = MessageEvalRecord.LOCAL_MODEL): List<Graded> = runBlocking {
        var answer: String? = null
        val fake = FakeAiProvider(provider, mapOf(model to AiPrice.FREE)) { AiCompletion.Completed(answer ?: error("asked about a row with no recorded answer"), AiUsage(100, 40)) }
        val calls = InMemoryAiCallLogRepository()
        val gateway = AiGateway(listOf(AiRoute.of(AiFeature.MESSAGES, fake, model)), AiCostGuard(InMemoryAiSpendLedger(), 0, CLOCK), calls, ALLOW_ALL_CONSENT, clock = CLOCK)
        val proposals = InMemoryMessageProposalRepository()
        val fallback = MessageFallback(gateway, proposals, clock = CLOCK, capacity = 10, dailyLimit = MessageFallback.MAX_DAILY_LIMIT)
        var offer: FallbackOffer? = null
        val sink = UnrecognisedMessageSink { m -> fallback.offer(m).also { offer = it } }
        val mandates = MandateService(InMemoryMandateRepository(), InMemoryTransactionRepository())
        val intake = IngestEventService(InMemoryIngestEventRepository(), CLOCK)
        val sms = SmsIntake(SmsParserRegistry(), intake, mandates, sink) { ZONE }
        val whatsapp = WhatsAppImportService(SmsParserRegistry(), intake, mandates, UserCalendar(InMemoryUserRepository()), CLOCK, sink)
        val userId = UUID.randomUUID()

        MessageGoldenSet.load().map { row ->
            offer = null
            answer = row.answer
            val asked = fake.requests.size
            val audited = calls.all.size
            val before = proposals.pending(userId, Int.MAX_VALUE).map { it.id }.toSet()
            if (row.expected == Expected.NotOffered) {
                when (row.channel) {
                    "sms" -> sms.accept(userId, SmsMessage(row.sender, row.text, row.receivedAt))
                    "whatsapp" -> whatsapp.import(userId, whatsAppExport(row))
                    else -> error("unknown channel ${row.channel}")
                }
            } else {
                offer = fallback.offer(unrecognised(userId, row))
            }
            val counts = if (offer == FallbackOffer.Queued) fallback.drain(1, Duration.ofMinutes(1)) else null
            val sent = fake.requests.drop(asked).singleOrNull()?.user
            val code = calls.all.drop(audited).singleOrNull()?.code?.value
            val outcome = when {
                offer is FallbackOffer.Withheld -> Outcome.WITHHELD
                counts == null -> Outcome.NOT_OFFERED
                counts.proposed == 1 -> Outcome.ACCEPTED
                counts.notTransactions == 1 -> Outcome.SAID_NOT
                else -> Outcome.REJECTED
            }
            val proposal = proposals.pending(userId, Int.MAX_VALUE).singleOrNull { it.id !in before }
            Graded(row, outcome, proposal, code, sent)
        }
    }

    /** The day the message arrived, in the user's zone. */
    fun receivedOn(row: GoldenMessage): LocalDate = row.receivedAt.atZone(ZONE).toLocalDate()

    /** [row] as a one-message WhatsApp chat export. */
    fun whatsAppExport(row: GoldenMessage): ByteArray =
        "${WHATSAPP_TIME.format(row.receivedAt.atZone(ZONE))} - ${row.sender}: ${row.text}\n".toByteArray()

    /** [row] as the intake hands an unreadable bank message to the fallback: the sender's bank, the user's zone, the text. */
    fun unrecognised(userId: UUID, row: GoldenMessage): UnrecognisedMessage {
        val (channel, bankCode) = when (row.channel) {
            "sms" -> MessageChannel.SMS to SenderId.parse(row.sender)?.let { PARSERS.bankFor(it.header) }
            "whatsapp" -> MessageChannel.WHATSAPP to (BankSenders.match(row.sender) as? BankSenders.Bank)?.code
            else -> error("unknown channel ${row.channel}")
        }
        return UnrecognisedMessage(userId, channel, "golden-${row.id}", row.receivedAt, ZONE, bankCode, row.text)
    }

    private val PARSERS = SmsParserRegistry()
}

/** The message-reading eval record (AOS-705, D-67): the scores the start-up gate reads, and their floor. */
object MessageEvalRecord {
    const val LOCAL_MODEL = "qwen3:4b"
    const val CLOUD_MODEL = "claude-haiku-5-5"
    const val EVALUATED_ON = "2026-10-18"

    val KEYS = listOf(
        AiEvalKey(AiFeature.MESSAGES, AiProviderKind.OLLAMA, LOCAL_MODEL, ReadMessageTask.promptVersion),
        AiEvalKey(AiFeature.MESSAGES, AiProviderKind.ANTHROPIC, CLOUD_MODEL, ReadMessageTask.promptVersion)
    )

    /** Fixed floors from the research (§6): a false transaction corrupts totals, so precision first. */
    val FIXED_FLOOR = mapOf(
        "transactionPrecision" to 0.99,
        "amountExact" to 0.98,
        "groundedAccepted" to 1.0,
        "hostileHeld" to 1.0,
        "droppedNeverAsked" to 1.0
    )

    fun metrics(graded: List<Graded>): Map<String, Double> {
        val accepted = graded.filter { it.outcome == Outcome.ACCEPTED }
        fun share(n: Int, of: Int) = if (of == 0) 1.0 else round(n.toDouble() / of)
        fun exp(g: Graded) = g.row.expected as? Expected.Transaction
        val transactions = graded.filter { exp(it) != null && "hostile" !in it.row.tags }
        val hostile = graded.filter { "hostile" in it.row.tags }
        val dropped = graded.filter { it.row.expected == Expected.NotOffered }
        fun right(g: Graded): Boolean {
            val e = exp(g) ?: return false
            val p = g.proposal ?: return false
            return p.amount.compareTo(e.amount) == 0 && p.direction == e.direction
        }
        return sortedMapOf(
            "transactionPrecision" to share(accepted.count { exp(it) != null }, accepted.size),
            "amountExact" to share(accepted.count { g -> exp(g)?.let { g.proposal?.amount?.compareTo(it.amount) == 0 } == true }, accepted.size),
            "directionExact" to share(accepted.count { g -> exp(g)?.direction == g.proposal?.direction }, accepted.size),
            "last4Exact" to share(accepted.count { g -> exp(g)?.let { it.last4 == g.proposal?.accountLast4 } == true }, accepted.size),
            "dateExact" to share(accepted.count { g ->
                exp(g)?.let { e -> g.proposal?.occurredAt?.atZone(MessageEvaluation.ZONE)?.toLocalDate() == (e.date ?: MessageEvaluation.receivedOn(g.row)) } == true
            }, accepted.size),
            "groundedAccepted" to share(accepted.count { g -> g.proposal?.let { grounded(g.row.text, it.amount) } == true }, accepted.size),
            "recall" to share(transactions.count(::right), transactions.size),
            "hostileHeld" to share(hostile.count { it.outcome != Outcome.ACCEPTED }, hostile.size),
            "droppedNeverAsked" to share(dropped.count { it.sent == null }, dropped.size)
        )
    }

    /** An independent check (not the validator's): the amount, without grouping, is written in the original text. */
    private fun grounded(text: String, amount: BigDecimal): Boolean {
        val flat = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replace(",", "")
        return amount.setScale(2).toPlainString() in flat || amount.stripTrailingZeros().toPlainString() in flat
    }

    fun floor(metrics: Map<String, Double>): Map<String, Double> =
        (metrics.filterKeys { it !in FIXED_FLOOR } + FIXED_FLOOR).toSortedMap()

    fun record(key: AiEvalKey, graded: List<Graded>, evaluatedOn: String = EVALUATED_ON): AiEvalRecord {
        val metrics = metrics(graded)
        return AiEvalRecord(
            feature = key.feature.key, provider = key.provider.key, model = key.model, promptVersion = key.promptVersion,
            answers = AiEvalAnswers.RECORDED_SYNTHETIC, evaluatedOn = evaluatedOn, goldenRows = graded.size,
            metrics = metrics, floor = floor(metrics)
        )
    }

    private fun round(v: Double) = Math.round(v * 10_000) / 10_000.0
}
