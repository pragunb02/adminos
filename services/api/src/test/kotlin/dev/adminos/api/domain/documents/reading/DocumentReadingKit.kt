package dev.adminos.api.domain.documents.reading

import dev.adminos.api.domain.ai.AiCircuitBreaker
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiConsentCheck
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ai.FakeAiProvider
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.documents.DocumentCategory
import dev.adminos.api.domain.documents.DocumentFileType
import dev.adminos.api.domain.documents.DocumentService
import dev.adminos.api.domain.documents.DocumentUpload
import dev.adminos.api.domain.documents.InMemoryDocumentRepository
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.documents.AesGcmDocumentSealer
import dev.adminos.api.infrastructure.documents.InMemoryDocumentStorage
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** A reader that answers with a fixed reading (or fails), and records what it was given. */
class FakeDocumentReader(var answer: () -> DocumentText) : DocumentReader {
    val calls = mutableListOf<Pair<Int, DocumentFileType>>()
    override suspend fun read(bytes: ByteArray, type: DocumentFileType): DocumentText {
        calls += bytes.size to type
        return answer()
    }
}

/** The documents vault, the reader and the AI gateway on in-memory parts, for AOS-717 tests. Synthetic data only. */
class DocumentReadingKit(aiReply: String? = null, consent: Boolean = true) {
    val owner: UUID = UUID.randomUUID()
    val documents = DocumentService(InMemoryDocumentRepository(), InMemoryDocumentStorage(),
        AesGcmDocumentSealer(TokenEncryptor(Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)))))
    val provider = FakeAiProvider(kind = AiProviderKind.OLLAMA, reply = { AiCompletion.Completed(aiReply ?: "{}", AiUsage(50, 20)) })
    val calls = InMemoryAiCallLogRepository()
    val gateway: AiGateway? = aiReply?.let {
        AiGateway(listOf(AiRoute.of(AiFeature.DOCUMENT_FIELDS, provider, FakeAiProvider.MODEL)), AiCostGuard(InMemoryAiSpendLedger(), 1_000_000), calls,
            AiConsentCheck { _, f -> consent && f == AiFeature.DOCUMENT_FIELDS }, AiCircuitBreaker())
    }
    val reader = FakeDocumentReader { reading(TEXT) }
    val service = DocumentFieldService(documents, reader, gateway)

    suspend fun upload(category: DocumentCategory = DocumentCategory.INSURANCE, user: UUID = owner) =
        documents.upload(user, DocumentUpload("%PDF-1.7\nsynthetic".toByteArray(), "policy.pdf", "Motor policy", category, null, null))

    companion object {
        val TEXT = """
            Example Mutual General Insurance Company Limited
            Policy No.: 9876543210
            Insured: Asha Example, Pune
            Valid till: 31/03/2027
            Total Premium: Rs. 12,500.00
        """.trimIndent()

        fun reading(text: String, vararg proposals: ReadProposal = ruleProposals(text)) =
            DocumentText(text, TextSource.TEXT_LAYER, 1, 1, false, proposals.toList())

        fun ruleProposals(text: String): Array<ReadProposal> = buildList {
            if ("31/03/2027" in text) add(ReadProposal(DocumentField.EXPIRES_ON, "2027-03-31", "31/03/2027", "Valid till", "expiry_cue"))
            if ("9876543210" in text) add(ReadProposal(DocumentField.NUMBER, "9876543210", "9876543210", "Policy No.", "number_cue"))
            if ("Rs. 12,500.00" in text) add(ReadProposal(DocumentField.AMOUNT, "12500.00", "Rs. 12,500.00", "Total premium", "amount_cue"))
        }.toTypedArray()
    }
}
