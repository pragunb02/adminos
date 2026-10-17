package dev.adminos.api.domain.financial.learning

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * How a user's model may be used (AOS-707):
 * - [APPLY]: it passed the held-out check on the user's own corrections, so a sure guess sets the category;
 * - [SUGGEST]: it didn't (or couldn't be checked yet), so its guesses are only suggestions for the Review queue.
 */
enum class ModelMode { APPLY, SUGGEST }

/** What training measured, stored with the model and shown in its status. Counts and ratios only. */
data class TrainingSummary(
    /** Rows learned from: the user's payees plus the synthetic prior. */
    val trainingRows: Int,
    /** The user's own corrected payees among them. */
    val corrections: Int,
    val categories: Int,
    /** The user's corrections that cross-validation could predict without having seen them. */
    val heldOut: Int,
    /** Of those held-out corrections the model was sure of, the share it got right; null when it was sure of none. */
    val heldOutPrecision: Double?,
    /** Held-out expected calibration error over every row; null when there was nothing held out. */
    val calibrationError: Double?,
    val temperature: Double
) {
    init {
        require(trainingRows >= 0 && corrections in 0..trainingRows && categories >= 0 && heldOut >= 0) { "Counts are not negative" }
        require(heldOutPrecision == null || heldOutPrecision in 0.0..1.0) { "Precision is a ratio" }
        require(calibrationError == null || calibrationError in 0.0..1.0) { "The calibration error is a ratio" }
        require(temperature > 0 && temperature.isFinite()) { "A temperature is positive" }
    }
}

/**
 * A user's serialised model (AOS-707, one per user). [checksum] is the SHA-256 of [blob], checked on every load,
 * so a damaged row is noticed instead of answering nonsense.
 */
class StoredCategoryModel(
    val userId: UUID,
    val algorithm: String,
    val formatVersion: Int,
    val blob: ByteArray,
    val checksum: String,
    val mode: ModelMode,
    val summary: TrainingSummary,
    val trainedAt: Instant
) {
    init {
        require(algorithm.isNotBlank()) { "A stored model names its algorithm" }
        require(formatVersion >= 1) { "A format version starts at 1" }
        require(blob.isNotEmpty()) { "A stored model has content" }
        require(checksum.matches(Regex("[0-9a-f]{64}"))) { "A checksum is a SHA-256 in hex" }
    }

    /** A short, stable name for this model in transaction metadata and logs. */
    val modelId: String get() = "$algorithm@${checksum.take(12)}"

    override fun equals(other: Any?): Boolean = other is StoredCategoryModel && userId == other.userId &&
        algorithm == other.algorithm && formatVersion == other.formatVersion && blob.contentEquals(other.blob) &&
        checksum == other.checksum && mode == other.mode && summary == other.summary && trainedAt == other.trainedAt

    override fun hashCode(): Int = 31 * userId.hashCode() + checksum.hashCode()

    override fun toString() = "StoredCategoryModel(userId=$userId, model=$modelId, mode=$mode, trainedAt=$trainedAt, $summary)"
}

/** Users' stored models (AOS-707): InMemory for dev and tests, Exposed on Postgres. */
interface CategoryModelRepository {
    suspend fun find(userId: UUID): StoredCategoryModel?

    /** The checksum alone (cheap): lets a cached model be reused while it is still the stored one. */
    suspend fun findChecksum(userId: UUID): String?

    /** Inserts or replaces the user's model. */
    suspend fun save(model: StoredCategoryModel)

    /** Removes the user's model (back to cold start); true when there was one. */
    suspend fun delete(userId: UUID): Boolean
}

class InMemoryCategoryModelRepository : CategoryModelRepository {
    private val store = ConcurrentHashMap<UUID, StoredCategoryModel>()

    override suspend fun find(userId: UUID): StoredCategoryModel? = store[userId]
    override suspend fun findChecksum(userId: UUID): String? = store[userId]?.checksum
    override suspend fun save(model: StoredCategoryModel) {
        store[model.userId] = model
    }
    override suspend fun delete(userId: UUID): Boolean = store.remove(userId) != null
}
