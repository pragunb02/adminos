package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import io.ktor.http.*
import io.ktor.http.content.*

/**
 * The shape every upload form must have (AOS-618, AUD-21): exactly the fields the route expects, each at most once,
 * at most one file, small text fields. Anything else is a 400 before the next part is read, so a form can't carry
 * extra payloads, repeat the file, or smuggle large text fields.
 *
 * The whole body is already capped before routing ([RequestBodyLimit]); this bounds what is kept from it.
 */
class UploadForm(
    private val fileField: String,
    private val textFields: Set<String>,
    private val maxTextLength: Int = 4_000
) {
    private val seen = mutableSetOf<String>()

    /** Checks [part] before the route reads it; throws a 400 [ApiException] when it doesn't belong. */
    fun check(part: PartData) {
        val name = part.name ?: throw bad("Every form field needs a name")
        if (!seen.add(name)) throw bad("'$name' was sent more than once")
        when (part) {
            is PartData.FileItem -> if (name != fileField) throw bad("Unexpected file field '${name.take(40)}'")
            is PartData.FormItem -> {
                if (name !in textFields) throw bad("Unexpected field '${name.take(40)}'")
                if (part.value.length > maxTextLength) throw bad("'$name' is too long")
            }
            else -> throw bad("Unexpected form part '${name.take(40)}'")
        }
    }

    private fun bad(message: String) = ApiException(HttpStatusCode.BadRequest, ApiError("UPLOAD_001", message))
}
