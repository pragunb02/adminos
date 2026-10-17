package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.learning.LearningFixtures.PHARMACIES
import dev.adminos.api.domain.financial.learning.LearningFixtures.input
import dev.adminos.api.domain.financial.learning.LearningFixtures.trainedModel
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CategoryModelCodecTest {

    private val model = trainedModel().model
    private val encoded = CategoryModelCodec.encode(model)

    private fun corrupt(bytes: ByteArray) = assertFailsWith<CorruptCategoryModelException> { CategoryModelCodec.decode(bytes, CategoryModelCodec.sha256(bytes)) }

    /** A hand-written blob in the format, to test each check the reader makes. */
    private fun blob(
        magic: Int = 0x414F5337, format: Int = 1, algorithm: String = SoftmaxRegressionModel.ALGORITHM, temperature: Double = 1.0,
        categories: List<String> = listOf("FOOD", "HEALTH"), features: List<String> = listOf("w:chai"), weight: Float = 0.5f, trailing: Boolean = false
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(buffer)).use { out ->
            out.writeInt(magic); out.writeInt(format); out.writeUTF(algorithm); out.writeDouble(temperature)
            out.writeInt(categories.size); categories.forEach { out.writeUTF(it) }
            out.writeInt(features.size); features.forEach { out.writeUTF(it); out.writeDouble(1.0) }
            categories.forEach { _ -> out.writeDouble(0.0) }
            categories.forEach { _ -> features.forEach { _ -> out.writeFloat(weight) } }
            if (trailing) out.writeByte(1)
        }
        return buffer.toByteArray()
    }

    @Test
    fun `a model reads back to the same answers`() {
        val decoded = CategoryModelCodec.decode(encoded.bytes, encoded.checksum)
        assertEquals(model.temperature, decoded.temperature)
        for (name in PHARMACIES + listOf("Zqxv", "Lucky Kirana Stores")) {
            assertEquals(model.predict(input(name)), decoded.predict(input(name)))
        }
        assertEquals(CategoryModelCodec.FORMAT_VERSION, encoded.formatVersion)
        assertTrue(encoded.checksum.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `the same model always encodes to the same bytes`() {
        assertEquals(encoded.checksum, CategoryModelCodec.encode(trainedModel().model).checksum)
    }

    @Test
    fun `a checksum mismatch is corrupt`() {
        val e = assertFailsWith<CorruptCategoryModelException> { CategoryModelCodec.decode(encoded.bytes, "0".repeat(64)) }
        assertEquals("checksum mismatch", e.message)
    }

    @Test
    fun `cut-short, non-gzip and trailing bytes are corrupt`() {
        corrupt(encoded.bytes.copyOf(encoded.bytes.size / 2))
        corrupt("not a model".toByteArray())
        corrupt(blob(trailing = true))
    }

    @Test
    fun `a wrong magic, format or algorithm is corrupt`() {
        assertEquals("not a category model", corrupt(blob(magic = 42)).message)
        assertEquals("unsupported format 2", corrupt(blob(format = 2)).message)
        assertEquals("unsupported algorithm knn-v1", corrupt(blob(algorithm = "knn-v1")).message)
    }

    @Test
    fun `unknown categories, impossible counts and bad numbers are corrupt`() {
        assertEquals("unknown category RENT", corrupt(blob(categories = listOf("FOOD", "RENT"))).message)
        assertTrue(corrupt(blob(categories = emptyList())).message.orEmpty().startsWith("categories out of range"))
        assertTrue(corrupt(blob(features = emptyList())).message.orEmpty().startsWith("features out of range"))
        assertTrue(corrupt(blob(categories = listOf("FOOD"))).message.orEmpty().startsWith("invalid model"))
        assertTrue(corrupt(blob(categories = listOf("FOOD", "TRANSFER"))).message.orEmpty().startsWith("invalid model"))
        assertTrue(corrupt(blob(weight = Float.NaN)).message.orEmpty().startsWith("invalid model"))
        assertTrue(corrupt(blob(temperature = 0.0)).message.orEmpty().startsWith("invalid model"))
        assertTrue(corrupt(blob(features = listOf("w:" + "x".repeat(300)))).message.orEmpty().startsWith("invalid model"))
    }

    @Test
    fun `a hand-written valid blob decodes`() {
        val decoded = CategoryModelCodec.decode(blob(), CategoryModelCodec.sha256(blob()))
        assertEquals(listOf(TransactionCategory.FOOD, TransactionCategory.HEALTH), decoded.categories)
    }

    @Test
    fun `only softmax models can be stored`() {
        val other = object : CategoryModel {
            override val algorithm = "fake"
            override val categories = listOf(TransactionCategory.FOOD, TransactionCategory.HEALTH)
            override fun predict(input: CategoryInput) = CategoryGuess(TransactionCategory.FOOD, 1.0)
            override fun distribution(input: CategoryInput) = mapOf(TransactionCategory.FOOD to 1.0, TransactionCategory.HEALTH to 0.0)
        }
        assertFailsWith<IllegalArgumentException> { CategoryModelCodec.encode(CalibratedCategoryModel(other, 1.0)) }
    }
}
