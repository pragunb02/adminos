package dev.adminos.api.domain.wealth

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** AOS-802: the wealth audit trail records codes and counts, never content. */
class WealthAuditTest {
    private val repo = InMemoryAuditRepository()
    private val audit = WealthAudit(AuditService(repo, CoroutineScope(Dispatchers.Unconfined)))

    @Test
    fun `an import is audited with counts and the reader code`() = runBlocking<Unit> {
        val user = UUID.randomUUID()
        val batch = UUID.randomUUID()
        audit.record(user, WealthAuditActions.IMPORT, "wealth_import_batch", batch, counts = mapOf("added" to 12, "blocked" to 1), codes = mapOf("reader" to "casparser"))
        val row = repo.findByUserId(user).single()
        assertEquals(WealthAuditActions.IMPORT, row.action)
        assertEquals(ActorType.USER, row.actor)
        assertEquals(batch, row.entityId)
        assertEquals("12", row.metadata?.get("added")?.jsonPrimitive?.content)
        assertEquals("casparser", row.metadata?.get("reader")?.jsonPrimitive?.content)
    }

    @Test
    fun `a system job is audited as the system`() = runBlocking<Unit> {
        val run = UUID.randomUUID()
        audit.record(null, WealthAuditActions.PRICE_FETCH, "price_fetch", run, counts = mapOf("stored" to 3), codes = mapOf("source" to "amfi"))
        val row = repo.findByEntity("price_fetch", run).single()
        assertEquals(ActorType.SYSTEM, row.actor)
        assertEquals(null, row.userId)
    }

    @Test
    fun `free text, identifiers and foreign actions are refused`() {
        assertFailsWith<IllegalArgumentException> { audit.record(null, "auth.login", "x", null) }
        assertFailsWith<IllegalArgumentException> { audit.record(null, WealthAuditActions.IMPORT, "x", null, codes = mapOf("folio" to "Folio 9000 1234")) }
        assertFailsWith<IllegalArgumentException> { audit.record(null, WealthAuditActions.IMPORT, "x", null, codes = mapOf("scheme" to "Example Fund - Direct")) }
    }
}
