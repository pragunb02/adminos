package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.learning.LearningFixtures.NOW
import dev.adminos.api.domain.financial.learning.LearningFixtures.trainedModel
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CategoryModelStoreTest {
    private val me = UUID.randomUUID()
    private val fitted = trainedModel()

    /** Counts what the store reads, so the cache can be checked. */
    private class CountingRepository(private val inner: CategoryModelRepository = InMemoryCategoryModelRepository()) : CategoryModelRepository by inner {
        val finds = AtomicInteger()
        val checksums = AtomicInteger()
        override suspend fun find(userId: UUID): StoredCategoryModel? = inner.find(userId).also { finds.incrementAndGet() }
        override suspend fun findChecksum(userId: UUID): String? = inner.findChecksum(userId).also { checksums.incrementAndGet() }
    }

    private fun stored(model: CalibratedCategoryModel = fitted.model, mode: ModelMode = ModelMode.APPLY, blob: ByteArray? = null): StoredCategoryModel {
        val e = CategoryModelCodec.encode(model)
        val bytes = blob ?: e.bytes
        return StoredCategoryModel(me, model.algorithm, e.formatVersion, bytes, CategoryModelCodec.sha256(bytes), mode,
            TrainingSummary(40, 32, 4, 32, 1.0, 0.05, 1.2), NOW)
    }

    @Test
    fun `no stored model is None`() = runBlocking<Unit> {
        assertEquals(LoadedModel.None, CategoryModelStore(InMemoryCategoryModelRepository()).load(me))
    }

    @Test
    fun `a stored model loads, and is cached for the time to live`() = runBlocking<Unit> {
        val repo = CountingRepository()
        val clock = TestClock(NOW)
        repo.save(stored())
        val store = CategoryModelStore(repo, clock, Duration.ofMinutes(10))
        val first = assertIs<LoadedModel.Ready>(store.load(me))
        assertEquals(ModelMode.APPLY, first.mode)
        assertTrue(first.modelId.startsWith("softmax-v1@"))
        store.load(me)
        assertEquals(1, repo.finds.get(), "cached")
        assertEquals(1, repo.checksums.get())

        clock.advance(Duration.ofMinutes(11))
        store.load(me)
        assertEquals(2, repo.checksums.get(), "re-checked after the time to live")
        assertEquals(1, repo.finds.get(), "same checksum: not decoded again")
    }

    @Test
    fun `a model retrained elsewhere is picked up after the time to live`() = runBlocking<Unit> {
        val repo = CountingRepository()
        val clock = TestClock(NOW)
        repo.save(stored())
        val store = CategoryModelStore(repo, clock, Duration.ofMinutes(10))
        store.load(me)
        repo.save(stored(mode = ModelMode.SUGGEST, model = trainedModel(LearningFixtures.localShopExamples(6)).model))
        clock.advance(Duration.ofMinutes(11))
        assertEquals(ModelMode.SUGGEST, assertIs<LoadedModel.Ready>(store.load(me)).mode)
        assertEquals(2, repo.finds.get())
    }

    @Test
    fun `a damaged model is Corrupt, not trusted and not an error`() = runBlocking<Unit> {
        val repo = InMemoryCategoryModelRepository()
        repo.save(stored(blob = "garbage".toByteArray()))
        val loaded = assertIs<LoadedModel.Corrupt>(CategoryModelStore(repo).load(me))
        assertTrue(loaded.reason.startsWith("unreadable model"), loaded.reason)
    }

    @Test
    fun `put saves and serves at once, remove goes back to cold start`() = runBlocking<Unit> {
        val repo = CountingRepository()
        val store = CategoryModelStore(repo)
        store.put(stored(), fitted.model)
        assertIs<LoadedModel.Ready>(store.load(me))
        assertEquals(0, repo.finds.get(), "served from the cache")
        assertEquals(stored(), store.stored(me))
        assertTrue(store.remove(me))
        assertEquals(LoadedModel.None, store.load(me))
        assertEquals(null, repo.find(me))
        assertEquals(false, store.remove(me))
    }

    @Test
    fun `a stored model validates itself and compares by content`() {
        val s = stored()
        assertEquals(s, stored())
        assertEquals(s.hashCode(), stored().hashCode())
        assertTrue(s != stored(mode = ModelMode.SUGGEST))
        assertTrue("blob" !in s.toString() && s.toString().contains(s.modelId))
        assertFailsWith<IllegalArgumentException> { StoredCategoryModel(me, " ", 1, s.blob, s.checksum, s.mode, s.summary, NOW) }
        assertFailsWith<IllegalArgumentException> { StoredCategoryModel(me, "a", 0, s.blob, s.checksum, s.mode, s.summary, NOW) }
        assertFailsWith<IllegalArgumentException> { StoredCategoryModel(me, "a", 1, ByteArray(0), s.checksum, s.mode, s.summary, NOW) }
        assertFailsWith<IllegalArgumentException> { StoredCategoryModel(me, "a", 1, s.blob, "nope", s.mode, s.summary, NOW) }
        assertFailsWith<IllegalArgumentException> { TrainingSummary(-1, 0, 0, 0, null, null, 1.0) }
        assertFailsWith<IllegalArgumentException> { TrainingSummary(1, 2, 0, 0, null, null, 1.0) }
        assertFailsWith<IllegalArgumentException> { TrainingSummary(1, 0, 0, 0, 1.5, null, 1.0) }
        assertFailsWith<IllegalArgumentException> { TrainingSummary(1, 0, 0, 0, null, -0.1, 1.0) }
        assertFailsWith<IllegalArgumentException> { TrainingSummary(1, 0, 0, 0, null, null, 0.0) }
    }
}
