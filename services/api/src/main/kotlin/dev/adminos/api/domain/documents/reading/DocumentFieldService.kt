package dev.adminos.api.domain.documents.reading

import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiRefusal
import dev.adminos.api.domain.ai.AiResult
import dev.adminos.api.domain.ai.PiiMasker
import dev.adminos.api.domain.documents.DocumentService
import org.slf4j.LoggerFactory
import java.util.UUID

/** Where a proposal came from. */
enum class ProposalFrom { RULES, AI }

/**
 * One field proposed to the owner (AOS-717). Nothing here is saved: the owner confirms the proposal, and the web then
 * saves it through `PATCH /documents/{id}` like a typed value.
 * - [value]: the ISO date, the issuer as printed, or the amount; for a number, its last 4 characters only.
 * - [context]: the line it was found on, for the owner to check, with numbers and IDs masked (never a full number).
 */
data class FieldProposal(val field: DocumentField, val value: String, val context: String, val label: String, val from: ProposalFrom) {
    override fun toString() = "FieldProposal(field=$field, from=$from)"
}

/** What happened with the optional AI step, in words the web can show. */
enum class AiStep {
    /** Asked; its grounded answers (if any) are among the proposals. */
    USED,
    /** The rules found every field the AI may look for. */
    NOT_NEEDED,
    /** Identity documents never go to any model (D-67). */
    IDENTITY,
    /** The server has the feature off, or it isn't wired. */
    OFF,
    /** The owner hasn't turned it on in Settings → AI features (AOS-704). */
    NO_CONSENT,
    /** Nothing could be sent (no candidate lines), or the redactor withheld it. */
    WITHHELD,
    /** The model's answer failed validation (ungrounded, wrong shape): ignored. */
    REJECTED,
    /** The model or its server wasn't available, or the monthly cap is reached. */
    UNAVAILABLE
}

data class FieldProposals(
    val source: TextSource,
    val pagesRead: Int,
    val pagesTotal: Int,
    val truncated: Boolean,
    val proposals: List<FieldProposal>,
    val ai: AiStep
)

/**
 * Proposes a vault document's fields (AOS-717): the parser reads the text (text layer or local OCR) and its rules
 * propose; each proposal is checked against the text here ([FieldGrounding]); then, for fields still missing, the
 * optional AI step asks the model through the [AiGateway] (feature flag, consent, redaction, cost cap, audit), unless
 * the document is an identity document. Rules always win over the model.
 *
 * The decrypted file and its text exist only in memory for this call: nothing is stored or logged but counts.
 */
class DocumentFieldService(
    private val documents: DocumentService,
    private val reader: DocumentReader,
    private val gateway: AiGateway?
) {
    private val logger = LoggerFactory.getLogger(DocumentFieldService::class.java)

    /** @throws dev.adminos.api.domain.documents.DocumentNotFoundException for anyone but the owner; DocumentReadException */
    suspend fun propose(userId: UUID, documentId: UUID): FieldProposals {
        val document = documents.get(userId, documentId)
        val file = documents.download(userId, documentId)
        val read = reader.read(file.bytes, file.type)
        val text = read.text
        val (grounded, dropped) = read.proposals.distinctBy { it.field }.partition { FieldGrounding.holds(it.field, it.value, it.evidence, text) }
        val fromRules = grounded.map { FieldProposal(it.field, shown(it.field, it.value), context(text, it.evidence, it.field), it.label, ProposalFrom.RULES) }
        val missing = DocumentFieldsTask.ASKABLE - grounded.map { it.field }.toSet()
        val identity = IdentityDocument.isIdentity(document.category, text)
        val (fromAi, step) = when {
            missing.isEmpty() -> emptyList<FieldProposal>() to AiStep.NOT_NEEDED
            identity -> emptyList<FieldProposal>() to AiStep.IDENTITY
            gateway == null -> emptyList<FieldProposal>() to AiStep.OFF
            else -> ask(gateway, userId, DocumentFieldsQuestion(text, missing, identity), text)
        }
        logger.info("Document {} read: source={} pages={}/{} rules={} dropped={} ai={} aiFields={}", documentId, read.source,
            read.pagesRead, read.pagesTotal, fromRules.size, dropped.size, step, fromAi.size)
        val proposals = (fromRules + fromAi.filter { a -> fromRules.none { it.field == a.field } }).sortedBy { it.field.ordinal }
        return FieldProposals(read.source, read.pagesRead, read.pagesTotal, read.truncated, proposals, step)
    }

    private suspend fun ask(gateway: AiGateway, userId: UUID, question: DocumentFieldsQuestion, text: String): Pair<List<FieldProposal>, AiStep> =
        when (val result = gateway.run(DocumentFieldsTask, userId, question)) {
            is AiResult.Answered -> result.value.proposals.map { FieldProposal(it.field, it.value, context(text, it.evidence, it.field), "", ProposalFrom.AI) } to AiStep.USED
            is AiResult.Rejected -> emptyList<FieldProposal>() to AiStep.REJECTED
            is AiResult.Unavailable -> emptyList<FieldProposal>() to AiStep.UNAVAILABLE
            is AiResult.Refused -> emptyList<FieldProposal>() to when (result.reason) {
                AiRefusal.FEATURE_OFF -> AiStep.OFF
                AiRefusal.NO_CONSENT -> AiStep.NO_CONSENT
                AiRefusal.WITHHELD -> AiStep.WITHHELD
                AiRefusal.CAP_REACHED -> AiStep.UNAVAILABLE
            }
        }

    companion object {
        const val MAX_CONTEXT = 120
        private val NOT_ALPHANUMERIC = Regex("[^A-Za-z0-9]")

        /** A number is shown and saved as its last 4 letters or digits only; other fields as they are. */
        fun shown(field: DocumentField, value: String): String =
            if (field == DocumentField.NUMBER) value.replace(NOT_ALPHANUMERIC, "").uppercase().takeLast(4) else value

        /**
         * The line holding [evidence], at most [MAX_CONTEXT] characters, masked: a proposed number as `••••` and its
         * last 4, and anything [PiiMasker] finds (other numbers to their last 4, IDs, phones, emails).
         */
        fun context(text: String, evidence: String, field: DocumentField): String {
            val at = text.indexOf(evidence).coerceAtLeast(0)
            val start = text.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
            val end = text.indexOf('\n', at + evidence.length).let { if (it < 0) text.length else it }
            val raw = text.substring(start, end)
            val line = (if (field == DocumentField.NUMBER) raw.replace(evidence, "••••" + shown(field, evidence)) else raw).trim()
            val window = if (line.length <= MAX_CONTEXT) line else {
                val from = (at - start - 40).coerceIn(0, line.length - MAX_CONTEXT)
                line.substring(from, from + MAX_CONTEXT)
            }
            return PiiMasker.mask(window).text
        }
    }
}
