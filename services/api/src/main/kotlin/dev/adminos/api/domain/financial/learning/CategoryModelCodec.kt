package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A stored model that can't be used: damaged, cut short, from an unknown format, or failing its checksum. */
class CorruptCategoryModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The bytes of a model and their SHA-256 (hex). */
class EncodedCategoryModel(val bytes: ByteArray, val checksum: String, val formatVersion: Int)

/**
 * Serialises a [CalibratedCategoryModel] over a [SoftmaxRegressionModel] (AOS-707): a small gzipped binary format,
 * versioned, read back strictly. Every count is bounded and every number checked, so a damaged blob fails with
 * [CorruptCategoryModelException] rather than building a model that answers nonsense. The format holds features
 * (n-grams of payee names) and weights: personal data with the same care as the transactions it came from.
 *
 * Layout (format 1): magic, format, algorithm, temperature, categories, features with their idf, biases, weights.
 */
object CategoryModelCodec {
    const val FORMAT_VERSION = 1
    private const val MAGIC = 0x414F5337 // "AOS7"
    private const val MAX_FEATURE_LENGTH = 256

    fun encode(model: CalibratedCategoryModel): EncodedCategoryModel {
        val inner = model.inner
        require(inner is SoftmaxRegressionModel) { "Only ${SoftmaxRegressionModel.ALGORITHM} models can be stored, not ${inner.algorithm}" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(buffer)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(inner.algorithm)
            out.writeDouble(model.temperature)
            out.writeInt(inner.categories.size)
            inner.categories.forEach { out.writeUTF(it.name) }
            out.writeInt(inner.features.size)
            inner.features.forEachIndexed { j, name -> out.writeUTF(name); out.writeDouble(inner.idf[j]) }
            inner.bias.forEach { out.writeDouble(it) }
            inner.weights.forEach { row -> row.forEach { out.writeFloat(it) } }
        }
        val bytes = buffer.toByteArray()
        return EncodedCategoryModel(bytes, sha256(bytes), FORMAT_VERSION)
    }

    /** Reads a model back, checking [checksum] first. Throws [CorruptCategoryModelException] for anything unusable. */
    fun decode(bytes: ByteArray, checksum: String): CalibratedCategoryModel {
        if (sha256(bytes) != checksum) throw CorruptCategoryModelException("checksum mismatch")
        return try {
            DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { read(it) }
        } catch (e: IOException) {
            throw CorruptCategoryModelException("unreadable model (${e::class.simpleName})", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptCategoryModelException("invalid model: ${e.message}", e)
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun read(input: DataInputStream): CalibratedCategoryModel {
        if (input.readInt() != MAGIC) throw CorruptCategoryModelException("not a category model")
        val format = input.readInt()
        if (format != FORMAT_VERSION) throw CorruptCategoryModelException("unsupported format $format")
        val algorithm = input.readUTF()
        if (algorithm != SoftmaxRegressionModel.ALGORITHM) throw CorruptCategoryModelException("unsupported algorithm $algorithm")
        val temperature = input.readDouble()
        val categories = List(bounded(input.readInt(), TransactionCategory.entries.size, "categories")) { category(input.readUTF()) }
        val count = bounded(input.readInt(), SoftmaxRegressionModel.MAX_FEATURES, "features")
        val features = ArrayList<String>(count)
        val idf = DoubleArray(count)
        repeat(count) { j ->
            features += input.readUTF().also { require(it.length in 1..MAX_FEATURE_LENGTH) { "feature name length" } }
            idf[j] = input.readDouble()
        }
        val bias = DoubleArray(categories.size) { input.readDouble() }
        val weights = Array(categories.size) { FloatArray(count) { input.readFloat() } }
        if (input.read() != -1) throw CorruptCategoryModelException("trailing bytes")
        return CalibratedCategoryModel(SoftmaxRegressionModel(categories, features, idf, bias, weights), temperature)
    }

    private fun bounded(n: Int, max: Int, what: String): Int {
        if (n !in 1..max) throw CorruptCategoryModelException("$what out of range: $n")
        return n
    }

    private fun category(name: String): TransactionCategory =
        TransactionCategory.entries.firstOrNull { it.name == name } ?: throw CorruptCategoryModelException("unknown category $name")
}
