package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditLog
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditRepositoryIntegrationTest {

    private val audit = ExposedAuditRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `audit log round-trips every actor with json states and ip`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val entityId = UUID.randomUUID()
        for (actor in ActorType.entries) {
            audit.save(AuditLog(userId = userId, actor = actor, action = "subscription.cancel", entityType = "subscription", entityId = entityId,
                beforeState = buildJsonObject { put("status", JsonPrimitive("active")) },
                afterState = buildJsonObject { put("status", JsonPrimitive("cancelled")) },
                ipAddress = "198.51.100.4"))
        }
        val logs = audit.findByUserId(userId)
        assertEquals(ActorType.entries.toSet(), logs.map { it.actor }.toSet())
        assertEquals(JsonPrimitive("cancelled"), logs.first().afterState?.get("status"))
        assertEquals("198.51.100.4", logs.first().ipAddress)
        assertEquals(ActorType.entries.size, audit.findByEntity("subscription", entityId).size)
    }
}
