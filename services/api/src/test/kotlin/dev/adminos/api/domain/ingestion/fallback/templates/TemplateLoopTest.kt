package dev.adminos.api.domain.ingestion.fallback.templates

import dev.adminos.api.config.AppConfig
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
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.fallback.ConfirmOutcome
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.InMemoryMessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.MessageFallback
import dev.adminos.api.domain.ingestion.fallback.MessageReviewService
import dev.adminos.api.domain.ingestion.fallback.ProposalConfirmedListener
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.evaluation.Expected
import dev.adminos.api.domain.ingestion.fallback.evaluation.MessageGoldenSet
import dev.adminos.api.domain.ingestion.sms.SenderId
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsIntakeResult
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.domain.ingestion.whatsapp.WhatsAppImportService
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AOS-706 end to end: the model reads a format, the user confirms three readings, the format is proposed, the user
 * approves it, and from then on code reads it with no model, exactly as confirmed. On the synthetic golden set too.
 */
class TemplateLoopTest {
    private val clock = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)
    private val zone: ZoneId = UserCalendar.DEFAULT_ZONE
    private val me = UUID(0, 5)
    private var reply: String = ""
    private val provider = FakeAiProvider(AiProviderKind.OLLAMA, mapOf("qwen3:4b" to AiPrice.FREE)) { AiCompletion.Completed(reply, AiUsage(1, 1)) }
    private val gateway = AiGateway(listOf(AiRoute.of(AiFeature.MESSAGES, provider, "qwen3:4b")), AiCostGuard(InMemoryAiSpendLedger(), 0, clock), InMemoryAiCallLogRepository(), ALLOW_ALL_CONSENT, clock = clock)
    private val proposals = InMemoryMessageProposalRepository()
    private val templates = InMemoryMessageTemplateRepository()
    private val events = InMemoryIngestEventRepository()
    private val intake = IngestEventService(events, clock)
    private val fallback = MessageFallback(gateway, proposals, clock = clock, dailyLimit = MessageFallback.MAX_DAILY_LIMIT)
    private val reader = TemplateReader(templates, intake, fallback)
    private val review = MessageReviewService(proposals, intake, clock, listOf(TemplateLearning(templates, clock)))
    private val templateReview = TemplateReviewService(templates, clock)

    private fun json(amount: String, merchant: String, date: String) =
        """{"kind":"transaction","direction":"debit","amount":"$amount","accountLast4":"4321","merchant":"$merchant","date":"$date"}"""

    private suspend fun readAndConfirm(text: String, answer: String, ref: String) {
        reply = answer
        assertEquals(FallbackOffer.Queued, reader.offer(UnrecognisedMessage(me, MessageChannel.SMS, ref, Instant.parse("2026-10-16T05:00:00Z"), zone, null, text)))
        assertEquals(1, fallback.drain(10, Duration.ofMinutes(1)).proposed)
        val p = proposals.pending(me, 10).single()
        assertIs<ConfirmOutcome.Confirmed>(review.confirm(me, p.id))
    }

    @Test
    fun `three confirmed readings teach a format that then reads without a model`() = runBlocking<Unit> {
        readAndConfirm("Card XX4321 charged Rs.249.00 at ZEPTO on 16-10-26.", json("249.00", "ZEPTO", "2026-10-16"), "a")
        readAndConfirm("Card XX4321 charged Rs.1,250.50 at BLINKIT on 16-10-26.", json("1250.50", "BLINKIT", "2026-10-16"), "b")
        assertTrue(templateReview.proposed(me).isEmpty())
        readAndConfirm("Card XX4321 charged Rs.99.00 at UBER INDIA on 16 Oct 2026.", json("99.00", "UBER INDIA", "2026-10-16"), "c")
        val proposed = templateReview.proposed(me).single()
        assertEquals("Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>.", proposed.shape.text)
        assertTrue(templateReview.approve(me, proposed.id))

        val asked = provider.requests.size
        assertEquals(FallbackOffer.ReadByTemplate(duplicate = false),
            reader.offer(UnrecognisedMessage(me, MessageChannel.SMS, "d", Instant.parse("2026-10-16T05:00:00Z"), zone, null, "Card XX4321 charged Rs.3,499.00 at CROMA on 16-10-26.")))
        assertEquals(asked, provider.requests.size, "no model was asked")
        val e = events.findByUser(me).single { it.sourceRef == "d" }
        assertEquals("CROMA", e.merchantRaw)
        assertEquals(0, java.math.BigDecimal("3499.00").compareTo(e.amount))
        assertEquals(proposed.parsedBy, e.metadata?.get("parsedBy"))
    }

    @Test
    fun `a failing listener never undoes a confirmation`() = runBlocking<Unit> {
        val broken = MessageReviewService(proposals, intake, clock, listOf(ProposalConfirmedListener { error("learning down") }))
        reply = json("249.00", "ZEPTO", "2026-10-16")
        reader.offer(UnrecognisedMessage(me, MessageChannel.SMS, "x", Instant.parse("2026-10-16T05:00:00Z"), zone, null, "Card XX4321 charged Rs.249.00 at ZEPTO on 16-10-26."))
        fallback.drain(10, Duration.ofMinutes(1))
        val p = proposals.pending(me, 10).single()
        assertEquals("Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>.", p.shape?.text, "the proposal keeps its masked shape")
        assertIs<ConfirmOutcome.Confirmed>(broken.confirm(me, p.id))
        assertEquals(1, events.findByUser(me).size)
    }

    @Test
    fun `the intakes count what templates read`() = runBlocking<Unit> {
        val t = MessageTemplate(userId = me, channel = MessageChannel.SMS, bankCode = null, direction = IngestDirection.DEBIT,
            shape = MessageShape("Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>."), status = TemplateStatus.PROPOSED, seen = 3, createdAt = clock.instant())
        templates.save(t)
        templateReview.approve(me, t.id)
        val waT = t.copy(id = UUID.randomUUID(), channel = MessageChannel.WHATSAPP, bankCode = null)
        templates.save(waT)
        templateReview.approve(me, waT.id)
        val mandates = MandateService(InMemoryMandateRepository(), InMemoryTransactionRepository())
        val sms = SmsIntake(SmsParserRegistry(), intake, mandates, dev.adminos.api.domain.financial.paylater.testPayLaterBills(),
            dev.adminos.api.domain.financial.trips.TravelBookings(dev.adminos.api.domain.financial.trips.InMemoryTravelBookingRepository()), dev.adminos.api.domain.balances.BalanceService(dev.adminos.api.domain.balances.InMemoryBalanceRepository()), reader) { zone }
        val text = "Card XX4321 charged Rs.249.00 at ZEPTO on 16-10-26."
        assertEquals(SmsIntakeResult.Accepted, sms.accept(me, SmsMessage("AD-FEDBNK-S", text, Instant.parse("2026-10-16T05:00:00Z"))))
        assertEquals(SmsIntakeResult.Duplicate, sms.accept(me, SmsMessage("VM-FEDBNK-S", text, Instant.parse("2026-10-16T05:01:00Z"))))
        assertEquals(null, SenderId.parse("AD-FEDBNK-S")?.let { SmsParserRegistry().bankFor(it.header) })

        val wa = WhatsAppImportService(SmsParserRegistry(), intake, mandates, UserCalendar(InMemoryUserRepository()), clock, reader)
        val chat = "16/10/26, 10:30 - Federal Bank: $text\n16/10/26, 10:31 - Federal Bank: Card XX4321 charged Rs.99.00 at UBER on 16-10-26."
        val first = wa.import(me, chat.toByteArray())
        assertEquals(2, first.accepted)
        assertEquals(0, first.duplicates)
        val again = wa.import(me, chat.toByteArray())
        assertEquals(0, again.accepted)
        assertEquals(2, again.duplicates)
    }

    /**
     * The research metric (§6): the share of messages a learnt template handles. On the golden set, every format the
     * model read right at least three times is approved; then each of those messages, offered again as a new message,
     * must be read by a template with exactly the confirmed fields, and no model is asked.
     */
    @Test
    fun `on the golden set, approved templates read every repeat of their format exactly`() = runBlocking<Unit> {
        val rows = MessageGoldenSet.load().filter { it.tags == setOf("positive") && it.channel == "sms" }
        var proposedRows = 0
        for (row in rows) {
            reply = row.answer ?: continue
            val bank = SenderId.parse(row.sender)?.let { SmsParserRegistry().bankFor(it.header) }
            val offer = reader.offer(UnrecognisedMessage(me, MessageChannel.SMS, "g1-${row.id}", row.receivedAt, zone, bank, row.text))
            if (offer != FallbackOffer.Queued) continue
            if (fallback.drain(1, Duration.ofMinutes(1)).proposed != 1) continue
            proposedRows++
            review.confirm(me, proposals.pending(me, 1).single().id)
        }
        val proposedFormats = templateReview.proposed(me)
        proposedFormats.forEach { templateReview.approve(me, it.id) }
        assertTrue(proposedFormats.size >= 5, "several formats learnt, was ${proposedFormats.size}")

        val asked = provider.requests.size
        var byTemplate = 0
        var wrong = 0
        for (row in rows) {
            val bank = SenderId.parse(row.sender)?.let { SmsParserRegistry().bankFor(it.header) }
            val offer = reader.offer(UnrecognisedMessage(me, MessageChannel.SMS, "g2-${row.id}", row.receivedAt, zone, bank, row.text))
            if (offer !is FallbackOffer.ReadByTemplate) continue
            byTemplate++
            val e = events.findByUser(me, 10_000).single { it.sourceRef == "g2-${row.id}" }
            val x = row.expected as Expected.Transaction
            if (e.amount.compareTo(x.amount) != 0 || e.direction != x.direction || e.accountHint != x.last4 || e.merchantRaw != x.merchant) wrong++
        }
        // Formats seen fewer than three times stay with the model; everything a template reads is exact.
        assertEquals(0, wrong)
        assertTrue(byTemplate >= proposedRows * 0.8, "templates read $byTemplate of $proposedRows repeats")
        assertEquals(asked, provider.requests.size, "no model call for a template-read message")
    }

    // ── Routes ──

    private val jwt = JwtService(AppConfig.load().auth)
    private val owner = User(id = me, email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val other = User(id = UUID(0, 6), email = "them@example.com", googleId = "g-2", role = UserRole.OWNER)

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { templateReviewRoutes(templateReview) } }
        }
        block()
    }

    private fun proposedTemplate(user: UUID = me) = MessageTemplate(userId = user, channel = MessageChannel.SMS, bankCode = "fedbnk", direction = IngestDirection.DEBIT,
        shape = MessageShape("Card XX<ACCT> charged Rs.<AMT> at <M>"), status = TemplateStatus.PROPOSED, seen = 3, createdAt = clock.instant()).also { runBlocking { templates.save(it) } }

    @Test
    fun `template review is owner-only and decides once`() = routeTest {
        val mine = proposedTemplate()
        val theirs = proposedTemplate(other.id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/review/templates").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/review/templates/${mine.id}/approve").status)
        val list = client.get("/api/v1/review/templates") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner)) }.bodyAsText()
        assertTrue("\"shape\":\"Card XX<ACCT> charged Rs.<AMT> at <M>\"" in list && "\"seen\":3" in list && theirs.id.toString() !in list, list)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/review/templates/${theirs.id}/approve") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner)) }.status)
        val ok = client.post("/api/v1/review/templates/${mine.id}/approve") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner)) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue("\"status\":\"approved\"" in ok.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/review/templates/${mine.id}/reject") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner)) }.status)
        val r = client.post("/api/v1/review/templates/${theirs.id}/reject") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(other)) }
        assertTrue("\"status\":\"rejected\"" in r.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/review/templates/nope/approve") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner)) }.status)
    }
}
