package dev.adminos.api.domain.documents.reading

import dev.adminos.api.domain.ai.AiPrompt
import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.documents.DocumentCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AOS-717: what the AI step may send (redactor), what it accepts (validator), and grounding. Synthetic text only. */
class DocumentFieldsTaskTest {
    private val text = """
        Example Mutual General Insurance Company Limited
        Private Car Package Policy
        Policy No.: 9876543210
        Insured: Asha Example, 12 Sample Road, Pune
        Date of Birth: 05/06/1990  Mobile: 98000 00012
        Cover runs out 31 March 2027 at midnight
        Contribution collected Rs. 12,500.00
        Email asha@example.com
    """.trimIndent()
    private val all = setOf(DocumentField.EXPIRES_ON, DocumentField.ISSUER, DocumentField.AMOUNT, DocumentField.NUMBER)
    private fun q(t: String = text, wanted: Set<DocumentField> = all, identity: Boolean = false) = DocumentFieldsQuestion(t, wanted, identity)
    private fun read(json: String, question: DocumentFieldsQuestion = q()) = DocumentFieldsTask.read(json, question)

    @Test
    fun `only candidate lines are sent, masked, and lines about people never are`() {
        val lines = DocumentFieldsTask.excerpt(text)
        assertTrue(lines.any { "31 March 2027" in it })
        assertTrue(lines.any { "Rs. 12,500.00" in it })
        assertTrue(lines.any { "Insurance Company Limited" in it })
        val sent = lines.joinToString("\n")
        listOf("Asha", "Sample Road", "05/06/1990", "98000", "asha@example.com", "9876543210").forEach { assertFalse(it in sent, "$it must not be sent") }
    }

    @Test
    fun `the prompt asks only for askable fields and never for the number`() {
        val p = DocumentFieldsTask.prompt(q()) as AiPrompt.Send
        val user = Json.parseToJsonElement(p.user).jsonObject
        assertEquals(listOf("expiresOn", "issuer", "amount"), user.getValue("wanted").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("expiresOn", "issuer", "amount"), p.schema.getValue("properties").jsonObject.keys)
        assertFalse("number" in p.schema.toString())
        assertEquals("Send(maxOutputTokens=400)", p.toString(), "a prompt never prints its content")
    }

    @Test
    fun `identity documents and empty questions are withheld before anything is built`() {
        assertEquals(DocumentFieldsTask.IDENTITY_DOCUMENT, (DocumentFieldsTask.prompt(q(identity = true)) as AiPrompt.Withhold).code)
        assertEquals(DocumentFieldsTask.NOTHING_TO_SEND, (DocumentFieldsTask.prompt(q(wanted = setOf(DocumentField.NUMBER))) as AiPrompt.Withhold).code)
        assertEquals(DocumentFieldsTask.NOTHING_TO_SEND, (DocumentFieldsTask.prompt(q(t = "Asha Example\nName: Kabir Sample")) as AiPrompt.Withhold).code)
    }

    @Test
    fun `a grounded answer is accepted field by field`() {
        val r = read("""{"expiresOn":{"value":"2027-03-31","evidence":"31 March 2027"},"issuer":{"value":"Example Mutual General Insurance Company Limited",
            "evidence":"Example Mutual General Insurance Company Limited"},"amount":{"value":"12500.00","evidence":"Rs. 12,500.00"}}""")
        val v = assertIs<AiReading.Valid<DocumentFieldsAnswer>>(r).value.proposals
        assertEquals(listOf(DocumentField.EXPIRES_ON, DocumentField.ISSUER, DocumentField.AMOUNT), v.map { it.field })
        assertIs<AiReading.Valid<DocumentFieldsAnswer>>(read("""{"expiresOn":null,"issuer":null,"amount":null}"""))
    }

    @Test
    fun `invented, mismatched or masked values reject the whole answer`() {
        val bad = mapOf(
            """{"expiresOn":{"value":"2028-03-31","evidence":"31 March 2027"},"issuer":null,"amount":null}""" to DocumentFieldsTask.UNGROUNDED,
            """{"expiresOn":{"value":"2027-03-31","evidence":"Valid till 31/03/2027"},"issuer":null,"amount":null}""" to DocumentFieldsTask.UNGROUNDED,
            """{"expiresOn":null,"issuer":null,"amount":{"value":"125000.00","evidence":"Rs. 12,500.00"}}""" to DocumentFieldsTask.UNGROUNDED,
            // the masked phone line was never sent, so evidence from it can't be grounded in what was sent
            """{"expiresOn":null,"issuer":{"value":"Mobile","evidence":"Mobile: 98000 00012"},"amount":null}""" to DocumentFieldsTask.UNGROUNDED,
            // a line that was never sent (no field word in it) can't be evidence, even though it is in the document
            """{"expiresOn":null,"issuer":{"value":"Package","evidence":"Private Car Package Policy"},"amount":null}""" to DocumentFieldsTask.UNGROUNDED,
            """{"expiresOn":null,"issuer":null}""" to DocumentFieldsTask.UNEXPECTED_FIELDS,
            """{"expiresOn":null,"issuer":null,"amount":null,"number":{"value":"9876543210","evidence":"9876543210"}}""" to DocumentFieldsTask.UNEXPECTED_FIELDS,
            """{"expiresOn":"2027-03-31","issuer":null,"amount":null}""" to DocumentFieldsTask.BAD_FIELD,
            """{"expiresOn":{"value":"2027-03-31","evidence":"31 March 2027","why":"x"},"issuer":null,"amount":null}""" to DocumentFieldsTask.BAD_FIELD,
            """not json""" to DocumentFieldsTask.NOT_JSON_OBJECT,
            "[".repeat(10_000) to DocumentFieldsTask.NOT_JSON_OBJECT,
        )
        for ((json, code) in bad) {
            val r = read(json)
            if (code == null) assertIs<AiReading.Valid<*>>(r, json) else assertEquals(code, assertIs<AiReading.Invalid>(r, json).code, json)
        }
    }

    @Test
    fun `an issuer must be a business name`() {
        val t = "Valid till 31/03/2027\nIssued by ABCZE1234F Insurance Company Limited"
        val r = read("""{"expiresOn":null,"issuer":{"value":"ABCZE1234F Insurance Company Limited","evidence":"ABCZE1234F Insurance Company Limited"},"amount":null}""",
            q(t = t))
        assertIs<AiReading.Invalid>(r) // masked in what was sent, and not a safe name either
    }

    @Test
    fun `grounding checks dates, amounts, numbers and issuers against the evidence`() {
        val t = "Valid till 31/03/2027 Rs. 1,25,000.50 Policy No.: 3001/12345678/00/000 HDFC ERGO"
        assertTrue(FieldGrounding.holds(DocumentField.EXPIRES_ON, "2027-03-31", "31/03/2027", t))
        assertFalse(FieldGrounding.holds(DocumentField.EXPIRES_ON, "2027-03-13", "31/03/2027", t))
        assertFalse(FieldGrounding.holds(DocumentField.EXPIRES_ON, "2027-03-31", "31/03/2028", t), "not in the text")
        assertFalse(FieldGrounding.holds(DocumentField.EXPIRES_ON, "2027-3-31", "31/03/2027", t))
        assertTrue(FieldGrounding.holds(DocumentField.AMOUNT, "125000.50", "Rs. 1,25,000.50", t))
        assertFalse(FieldGrounding.holds(DocumentField.AMOUNT, "125000.00", "Rs. 1,25,000.50", t), "the paise printed must match")
        assertFalse(FieldGrounding.holds(DocumentField.AMOUNT, "0", "Rs. 1,25,000.50", t))
        assertTrue(FieldGrounding.holds(DocumentField.NUMBER, "3001/12345678/00/000", "3001/12345678/00/000", t))
        assertFalse(FieldGrounding.holds(DocumentField.NUMBER, "3001", "3001/12345678/00/000", t))
        assertTrue(FieldGrounding.holds(DocumentField.ISSUER, "HDFC ERGO", "HDFC ERGO", t))
        assertFalse(FieldGrounding.holds(DocumentField.ISSUER, "HDFC ERGO", "", t))
        assertTrue(FieldGrounding.dateIn("31-Mar-2027", LocalDate.of(2027, 3, 31)))
        assertTrue(FieldGrounding.amountIn("INR 90", BigDecimal("90.00")))
        assertFalse(FieldGrounding.amountIn("INR 90", BigDecimal("90.50")))
    }

    @Test
    fun `identity documents are recognised by category or by their text`() {
        assertTrue(IdentityDocument.isIdentity(DocumentCategory.IDENTITY, "anything"))
        assertTrue(IdentityDocument.isIdentity(DocumentCategory.OTHER, "REPUBLIC OF INDIA\nPassport No."))
        assertTrue(IdentityDocument.isIdentity(DocumentCategory.OTHER, "Permanent Account Number"))
        assertTrue(IdentityDocument.isIdentity(DocumentCategory.OTHER, "ID ABCZE1234F"), "a PAN shape anywhere")
        assertFalse(IdentityDocument.isIdentity(DocumentCategory.INSURANCE, text))
    }
}
