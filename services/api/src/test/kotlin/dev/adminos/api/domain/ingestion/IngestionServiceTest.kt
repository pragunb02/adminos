package dev.adminos.api.domain.ingestion

import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

/**
 * Unit tests for IngestionService — SMS batch ingestion and sync sessions.
 * Validates Requirements 3.1, 3.2, 3.6, 5.1
 */
class IngestionServiceTest {

    private lateinit var syncRepo: InMemorySyncSessionRepository
    private lateinit var service: IngestionService

    @BeforeTest
    fun setup() {
        syncRepo = InMemorySyncSessionRepository()
        service = IngestionService(syncRepo)
    }

    @Test
    fun `ingestSmsBatch creates sync session with correct item count`() = runBlocking {
        val userId = UUID.randomUUID()
        val connectionId = UUID.randomUUID()
        val records = listOf(
            SmsRecord(merchant = "ZOMATO", amount = 450.0, date = "2025-01-15", accountLast4 = "4521"),
            SmsRecord(merchant = "UBER", amount = 120.0, date = "2025-01-15", accountLast4 = "4521")
        )

        val session = service.ingestSmsBatch(userId, connectionId, records)

        assertEquals(SyncSessionStatus.QUEUED, session.status)
        assertEquals(2, session.totalItems)
        assertEquals(0, session.processedItems)
        assertEquals(userId, session.userId)
    }

    @Test
    fun `ingestSmsBatch rejects records with raw text`() = runBlocking {
        val userId = UUID.randomUUID()
        val connectionId = UUID.randomUUID()
        val records = listOf(
            SmsRecord(
                merchant = "ZOMATO",
                amount = 450.0,
                date = "2025-01-15",
                accountLast4 = "4521",
                rawText = "Your account debited by Rs 450"
            )
        )

        assertFailsWith<IllegalArgumentException> {
            service.ingestSmsBatch(userId, connectionId, records)
        }
    }

    @Test
    fun `getSyncSession returns session by ID`() = runBlocking {
        val userId = UUID.randomUUID()
        val connectionId = UUID.randomUUID()
        val records = listOf(
            SmsRecord(merchant = "ZOMATO", amount = 450.0, date = "2025-01-15", accountLast4 = "4521")
        )

        val created = service.ingestSmsBatch(userId, connectionId, records)
        val fetched = service.getSyncSession(created.id)

        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals(1, fetched.totalItems)
    }

    @Test
    fun `getSyncSession returns null for unknown ID`() = runBlocking {
        val result = service.getSyncSession(UUID.randomUUID())
        assertNull(result)
    }

    @Test
    fun `getUserSyncSessions returns sessions for user only`() = runBlocking {
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()
        val connId = UUID.randomUUID()
        val records = listOf(
            SmsRecord(merchant = "X", amount = 1.0, date = "2025-01-01", accountLast4 = "1234")
        )

        service.ingestSmsBatch(user1, connId, records)
        service.ingestSmsBatch(user1, connId, records)
        service.ingestSmsBatch(user2, connId, records)

        val user1Sessions = service.getUserSyncSessions(user1)
        val user2Sessions = service.getUserSyncSessions(user2)

        assertEquals(2, user1Sessions.size)
        assertEquals(1, user2Sessions.size)
    }

    @Test
    fun `SmsBatchRequest accepts batch over 100 items at model level`() {
        // Validation moved to route layer — model is a plain DTO
        val request = SmsBatchRequest(
            deviceId = "dev1",
            batch = (1..101).map {
                SmsRecord(merchant = "M$it", amount = 1.0, date = "2025-01-01", accountLast4 = "1234")
            }
        )
        assertEquals(101, request.batch.size)
    }

    @Test
    fun `SmsBatchRequest accepts empty batch at model level`() {
        // Validation moved to route layer — model is a plain DTO
        val request = SmsBatchRequest(deviceId = "dev1", batch = emptyList())
        assertEquals(0, request.batch.size)
    }
}
