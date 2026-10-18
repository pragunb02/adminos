package dev.adminos.api.domain.documents.reading

import dev.adminos.api.domain.documents.DocumentCategory
import dev.adminos.api.domain.documents.DocumentNotFoundException
import dev.adminos.api.domain.documents.reading.DocumentReadingKit.Companion.TEXT
import dev.adminos.api.domain.documents.reading.DocumentReadingKit.Companion.reading
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-717: rules first and grounded, the AI step only for what's missing, identity never sent, numbers never shown. */
class DocumentFieldServiceTest {
    private val issuerReply = """{"issuer":{"value":"Example Mutual General Insurance Company Limited","evidence":"Example Mutual General Insurance Company Limited"}}"""

    @Test
    fun `rule proposals are grounded and the number is only its last 4`() = runBlocking<Unit> {
        val k = DocumentReadingKit()
        val d = k.upload()
        val r = k.service.propose(k.owner, d.id)
        assertEquals(listOf(DocumentField.EXPIRES_ON, DocumentField.NUMBER, DocumentField.AMOUNT), r.proposals.map { it.field })
        val number = r.proposals.single { it.field == DocumentField.NUMBER }
        assertEquals("3210", number.value)
        assertFalse("9876543210" in number.context, "the context never shows the full number: ${number.context}")
        assertTrue("3210" in number.context)
        assertEquals(AiStep.OFF, r.ai, "no gateway: the AI step is off")
        assertEquals(1, k.reader.calls.size)
    }

    @Test
    fun `a rule proposal not grounded in the text is dropped`() = runBlocking<Unit> {
        val k = DocumentReadingKit()
        k.reader.answer = { reading(TEXT, ReadProposal(DocumentField.EXPIRES_ON, "2030-01-01", "31/03/2027", "", "x"),
            ReadProposal(DocumentField.NUMBER, "1111222233", "1111222233", "", "x")) }
        assertEquals(emptyList(), k.service.propose(k.owner, k.upload().id).proposals)
    }

    @Test
    fun `the AI step asks only for missing fields and the rules always win`() = runBlocking<Unit> {
        val k = DocumentReadingKit(aiReply = issuerReply)
        val r = k.service.propose(k.owner, k.upload().id)
        assertEquals(AiStep.USED, r.ai)
        assertEquals(ProposalFrom.AI, r.proposals.single { it.field == DocumentField.ISSUER }.from)
        assertEquals(ProposalFrom.RULES, r.proposals.single { it.field == DocumentField.EXPIRES_ON }.from)
        val sent = k.provider.requests.single()
        assertTrue(""""wanted":["issuer"]""" in sent.user, sent.user)
        listOf("9876543210", "Asha", "Pune").forEach { assertFalse(it in sent.user, "$it must not be sent") }
    }

    @Test
    fun `nothing is asked when the rules found everything`() = runBlocking<Unit> {
        val k = DocumentReadingKit(aiReply = issuerReply)
        k.reader.answer = { reading(TEXT, *DocumentReadingKit.ruleProposals(TEXT),
            ReadProposal(DocumentField.ISSUER, "Example Mutual General Insurance Company Limited", "Example Mutual General Insurance Company Limited", "", "known_issuer")) }
        assertEquals(AiStep.NOT_NEEDED, k.service.propose(k.owner, k.upload().id).ai)
        assertTrue(k.provider.requests.isEmpty())
    }

    @Test
    fun `identity documents never reach a model, by category or by text`() = runBlocking<Unit> {
        val byCategory = DocumentReadingKit(aiReply = issuerReply)
        assertEquals(AiStep.IDENTITY, byCategory.service.propose(byCategory.owner, byCategory.upload(DocumentCategory.IDENTITY).id).ai)
        val byText = DocumentReadingKit(aiReply = issuerReply)
        byText.reader.answer = { reading("REPUBLIC OF INDIA\nPassport No.\nZ1234567\nDate of Expiry 01/12/2031") }
        assertEquals(AiStep.IDENTITY, byText.service.propose(byText.owner, byText.upload(DocumentCategory.OTHER).id).ai)
        assertTrue(byCategory.provider.requests.isEmpty() && byText.provider.requests.isEmpty())
    }

    @Test
    fun `without consent nothing is sent and an ungrounded answer is ignored`() = runBlocking<Unit> {
        val noConsent = DocumentReadingKit(aiReply = issuerReply, consent = false)
        assertEquals(AiStep.NO_CONSENT, noConsent.service.propose(noConsent.owner, noConsent.upload().id).ai)
        assertTrue(noConsent.provider.requests.isEmpty())
        val invented = DocumentReadingKit(aiReply = """{"issuer":{"value":"Imaginary Assurance Ltd","evidence":"Imaginary Assurance Ltd"}}""")
        val r = invented.service.propose(invented.owner, invented.upload().id)
        assertEquals(AiStep.REJECTED, r.ai)
        assertTrue(r.proposals.none { it.field == DocumentField.ISSUER })
    }

    @Test
    fun `another user's document is not found and is never read`() = runBlocking<Unit> {
        val k = DocumentReadingKit()
        val d = k.upload()
        assertFailsWith<DocumentNotFoundException> { k.service.propose(UUID.randomUUID(), d.id) }
        assertFailsWith<DocumentNotFoundException> { k.service.propose(k.owner, UUID.randomUUID()) }
        assertTrue(k.reader.calls.isEmpty())
    }

    @Test
    fun `contexts are masked and bounded`() {
        val line = "Policy No.: 9876543210 Mobile 98000 00012 email a@example.com " + "x".repeat(300)
        val c = DocumentFieldService.context(line, "9876543210", DocumentField.NUMBER)
        assertTrue(c.length <= DocumentFieldService.MAX_CONTEXT + 10, c)
        listOf("9876543210", "98000 00012", "a@example.com").forEach { assertFalse(it in c, "$it in $c") }
        assertEquals("AB12", DocumentFieldService.shown(DocumentField.NUMBER, "ka-01/ab12"))
    }
}
