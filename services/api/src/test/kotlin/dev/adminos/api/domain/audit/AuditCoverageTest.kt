package dev.adminos.api.domain.audit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AuditService fire-and-forget logging and the in-memory audit store (AOS-312). */
class AuditCoverageTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Records saves and signals each one; can be told to fail. */
    private class RecordingRepository(private val fail: Boolean = false) : AuditRepository {
        val inner = InMemoryAuditRepository()
        val attempted = CompletableDeferred<AuditLog>()
        override suspend fun save(auditLog: AuditLog) {
            attempted.complete(auditLog)
            if (fail) throw IllegalStateException("audit table unavailable")
            inner.save(auditLog)
        }
        override suspend fun findByUserId(userId: UUID, limit: Int) = inner.findByUserId(userId, limit)
        override suspend fun findByEntity(entityType: String, entityId: UUID) = inner.findByEntity(entityType, entityId)
    }

    @Test
    fun `a system entry without a user is written in the background`() = runBlocking<Unit> {
        val repo = RecordingRepository()
        AuditService(repo, scope).log(actor = ActorType.SYSTEM, action = AuditActions.SYNC_START, entityType = "sync_session")
        val saved = withTimeout(5_000) { repo.attempted.await() }
        assertNull(saved.userId)
        assertEquals(ActorType.SYSTEM, saved.actor)
    }

    @Test
    fun `a failing audit write never reaches the caller`() = runBlocking<Unit> {
        val repo = RecordingRepository(fail = true)
        AuditService(repo, scope).log(userId = UUID.randomUUID(), actor = ActorType.USER, action = AuditActions.AUTH_LOGIN, entityType = "session")
        withTimeout(5_000) { repo.attempted.await() }
        assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive, "the audit scope survives the failure")
    }

    @Test
    fun `entries are found by entity type and id`() = runBlocking<Unit> {
        val repo = InMemoryAuditRepository()
        val id = UUID.randomUUID()
        repo.save(AuditLog(actor = ActorType.USER, action = AuditActions.CONNECTION_CREATE, entityType = "connection", entityId = id))
        repo.save(AuditLog(actor = ActorType.USER, action = AuditActions.CONNECTION_CREATE, entityType = "connection", entityId = UUID.randomUUID()))
        repo.save(AuditLog(actor = ActorType.USER, action = AuditActions.SUBSCRIPTION_FLAG, entityType = "subscription", entityId = id))
        assertEquals(1, repo.findByEntity("connection", id).size)
    }

    @Test
    fun `entries are found by user, newest first, up to the limit`() = runBlocking<Unit> {
        val repo = InMemoryAuditRepository()
        val user = UUID.randomUUID()
        repo.save(AuditLog(userId = user, actor = ActorType.USER, action = AuditActions.AUTH_LOGIN, entityType = "session"))
        repo.save(AuditLog(userId = user, actor = ActorType.USER, action = AuditActions.AUTH_LOGOUT, entityType = "session"))
        assertEquals(listOf(AuditActions.AUTH_LOGOUT), repo.findByUserId(user, 1).map { it.action })
    }
}
