package dev.adminos.api.domain.documents.reading

import dev.adminos.api.domain.documents.DocumentFileType

/** The fields a vault document can have proposed (AOS-717). The wire names are the parser's and the web's. */
enum class DocumentField(val wire: String) {
    EXPIRES_ON("expiresOn"), NUMBER("number"), ISSUER("issuer"), AMOUNT("amount");

    companion object {
        fun fromWire(raw: String): DocumentField? = entries.firstOrNull { it.wire == raw }
    }
}

/** How the text was obtained: the PDF's own text layer (exact), OCR of a scan or photo, or both across pages. */
enum class TextSource { TEXT_LAYER, OCR, MIXED }

/** A proposal from the parser's deterministic rules: [evidence] is an exact substring of the text. */
data class ReadProposal(val field: DocumentField, val value: String, val evidence: String, val label: String, val rule: String) {
    override fun toString() = "ReadProposal(field=$field, rule=$rule)" // the value can be a document number
}

/**
 * What the parser read from one document. [text] is held in memory for grounding and the optional AI step, then
 * dropped: it is never stored, logged or returned to the browser (D-33, D-67).
 */
class DocumentText(
    val text: String,
    val source: TextSource,
    val pagesRead: Int,
    val pagesTotal: Int,
    val truncated: Boolean,
    val proposals: List<ReadProposal>
) {
    override fun toString() = "DocumentText(source=$source, pages=$pagesRead/$pagesTotal, chars=${text.length}, proposals=${proposals.size})"
}

/** Reads a document's text and the rules' proposals (the Python parser service, AOS-717). Never stores anything. */
interface DocumentReader {
    /** @throws DocumentReadException */
    suspend fun read(bytes: ByteArray, type: DocumentFileType): DocumentText
}

/**
 * The document couldn't be read. [code] is the parser's stable code (`IMAGE_TOO_LARGE`, `UNSUPPORTED_IMAGE`…) or
 * `READER_UNAVAILABLE`; [clientError] says whether the file is the problem (422) or the reader (503).
 * The message never quotes the document.
 */
class DocumentReadException(val code: String, message: String, val clientError: Boolean) : RuntimeException(message)
