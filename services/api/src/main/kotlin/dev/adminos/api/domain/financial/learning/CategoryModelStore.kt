package dev.adminos.api.domain.financial.learning

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A user's model as the categoriser can use it. */
sealed interface LoadedModel {
    /** Cold start, or learning is off for this user: the learned step does nothing. */
    data object None : LoadedModel

    /** The stored model couldn't be read; it is ignored (logged) until the next training replaces it. */
    data class Corrupt(val reason: String) : LoadedModel

    data class Ready(val model: CategoryModel, val mode: ModelMode, val modelId: String) : LoadedModel
}

/**
 * Loads users' models through a small cache (AOS-707), so the ingestion pipeline doesn't decode a model per
 * transaction. An entry is trusted for [ttl]; after that only the stored checksum is read, and the model is
 * decoded again only if it changed (another instance retrained it). Training puts its model straight in.
 */
class CategoryModelStore(
    private val repository: CategoryModelRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10)
) {
    private val logger = LoggerFactory.getLogger(CategoryModelStore::class.java)

    private data class Entry(val checksum: String?, val loaded: LoadedModel, val checkedAt: Instant)

    private val cache = ConcurrentHashMap<UUID, Entry>()

    suspend fun load(userId: UUID): LoadedModel {
        val now = clock.instant()
        val cached = cache[userId]
        if (cached != null && Duration.between(cached.checkedAt, now) < ttl) return cached.loaded
        val checksum = repository.findChecksum(userId)
        if (cached != null && cached.checksum == checksum) return cached.copy(checkedAt = now).also { cache[userId] = it }.loaded
        val loaded = if (checksum == null) LoadedModel.None else read(userId)
        cache[userId] = Entry(checksum, loaded, now)
        return loaded
    }

    /** Saves a freshly trained model and caches it, so the next transaction uses it at once. */
    suspend fun put(stored: StoredCategoryModel, model: CategoryModel) {
        repository.save(stored)
        cache[stored.userId] = Entry(stored.checksum, LoadedModel.Ready(model, stored.mode, stored.modelId), clock.instant())
    }

    /** The stored row with its training summary (read straight from the repository, for status pages). */
    suspend fun stored(userId: UUID): StoredCategoryModel? = repository.find(userId)

    /** Back to cold start: the stored model is removed. */
    suspend fun remove(userId: UUID): Boolean {
        cache[userId] = Entry(null, LoadedModel.None, clock.instant())
        return repository.delete(userId)
    }

    private suspend fun read(userId: UUID): LoadedModel {
        val stored = repository.find(userId) ?: return LoadedModel.None
        return try {
            LoadedModel.Ready(CategoryModelCodec.decode(stored.blob, stored.checksum), stored.mode, stored.modelId)
        } catch (e: CorruptCategoryModelException) {
            // Visible, not silent: logged here and reported by the model status until the next training replaces it.
            logger.warn("The category model of user {} is unusable and is ignored until it is retrained: {}", userId, e.message)
            LoadedModel.Corrupt(e.message ?: "unreadable")
        }
    }
}
