package dev.adminos.api.domain.financial.links

import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.InfraIngestTestKit.routeTest
import dev.adminos.api.InfraIngestTestKit.tokenFor
import dev.adminos.api.InfraIngestTestKit.txn
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Suggestion review edge cases and the in-memory link store (AOS-312). */
class LinksSuggestionsCoverageTest {

    private val owner = user()
    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val service = SuggestionService(links, txns)

    private fun suggest(primaryId: UUID, relatedId: UUID?, relation: LinkRelation = LinkRelation.DUPLICATE, userId: UUID = owner.id, kind: LinkKind = LinkKind.SUGGESTED) =
        runBlocking { links.save(TransactionLink(userId = userId, transactionId = primaryId, relatedTransactionId = relatedId, source = "gmail",
            kind = kind, relation = relation, score = 6, reason = "same amount", rulesVersion = TransactionMatcher.RULES_VERSION)) }

    private fun saved(type: TransactionType, amount: String = "249.00") = runBlocking { txns.save(txn(owner.id, type, amount = amount)) }

    @Test
    fun `suggestions whose transactions are gone or missing are skipped`() = runBlocking<Unit> {
        val a = saved(TransactionType.DEBIT)
        suggest(UUID.randomUUID(), a.id)   // primary deleted
        suggest(a.id, UUID.randomUUID())   // related deleted
        suggest(a.id, null)                // no related side
        assertTrue(service.suggestions(owner.id).isEmpty())
    }

    @Test
    fun `suggestions can be filtered by relation`() = runBlocking<Unit> {
        val a = saved(TransactionType.DEBIT); val b = saved(TransactionType.CREDIT)
        suggest(a.id, b.id, LinkRelation.TRANSFER); suggest(a.id, b.id, LinkRelation.REFUND)
        assertEquals(listOf(LinkRelation.TRANSFER), service.suggestions(owner.id, LinkRelation.TRANSFER).map { it.link.relation })
        assertEquals(2, service.suggestions(owner.id).size)
    }

    @Test
    fun `confirming a suggestion whose transaction is gone does nothing`() = runBlocking<Unit> {
        val a = saved(TransactionType.DEBIT)
        val link = suggest(a.id, UUID.randomUUID())
        assertFalse(service.confirm(owner.id, link.id))
        assertEquals(LinkKind.SUGGESTED, links.findById(link.id)!!.kind)
    }

    @Test
    fun `confirming a suggestion without a related side does nothing`() = runBlocking<Unit> {
        assertFalse(service.confirm(owner.id, suggest(saved(TransactionType.DEBIT).id, null).id))
    }

    @Test
    fun `a refund suggestion listed from the credit's side is applied to the right pair`() = runBlocking<Unit> {
        val debit = txns.save(txn(owner.id, TransactionType.DEBIT, amount = "999.00").copy(category = TransactionCategory.SHOPPING))
        val credit = saved(TransactionType.CREDIT, "999.00")
        assertTrue(service.confirm(owner.id, suggest(credit.id, debit.id, LinkRelation.REFUND).id))
        val c = txns.findById(credit.id)!!
        assertEquals(TransactionNature.REFUND, c.nature)
        assertEquals(TransactionCategory.SHOPPING, c.category)
    }

    @Test
    fun `a duplicate confirmed without an event id keeps the link's event id`() = runBlocking<Unit> {
        val a = saved(TransactionType.DEBIT); val b = saved(TransactionType.DEBIT)
        val eventId = UUID.randomUUID()
        val link = links.save(suggest(a.id, b.id).copy(ingestEventId = eventId))
        assertTrue(service.confirm(owner.id, link.id))
        val merged = links.findById(link.id)!!
        assertEquals(LinkKind.MERGED, merged.kind); assertEquals(eventId, merged.ingestEventId); assertNull(merged.relatedTransactionId)
        assertNull(txns.findById(b.id))
    }

    @Test
    fun `only the owner's pending suggestions can be decided`() = runBlocking<Unit> {
        val a = saved(TransactionType.DEBIT); val b = saved(TransactionType.DEBIT)
        assertFalse(service.reject(owner.id, suggest(a.id, b.id, userId = UUID.randomUUID()).id), "someone else's")
        assertFalse(service.reject(owner.id, suggest(a.id, b.id, kind = LinkKind.MERGED).id), "already decided")
        assertFalse(service.reject(owner.id, UUID.randomUUID()), "unknown")
        assertTrue(service.reject(owner.id, suggest(a.id, b.id).id))
    }

    // ── In-memory link store ──

    @Test
    fun `links are found from either side of the pair`() = runBlocking<Unit> {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val link = suggest(a, b)
        assertEquals(listOf(link), links.findByTransaction(a)); assertEquals(listOf(link), links.findByTransaction(b))
        assertTrue(links.findByTransaction(UUID.randomUUID()).isEmpty())
    }

    @Test
    fun `merged sources count only merged duplicates of the asked transactions`() = runBlocking<Unit> {
        val a = UUID.randomUUID()
        suggest(a, null, kind = LinkKind.MERGED)                                  // counted: gmail
        suggest(a, null, kind = LinkKind.SUGGESTED)                               // pending
        suggest(a, null, relation = LinkRelation.TRANSFER, kind = LinkKind.MERGED) // a transfer, not a duplicate
        suggest(UUID.randomUUID(), null, kind = LinkKind.MERGED)                  // another transaction
        assertEquals(mapOf(a to setOf("gmail")), links.mergedSources(listOf(a)))
    }

    @Test
    fun `findByUser filters by user, kind and relation`() = runBlocking<Unit> {
        val a = UUID.randomUUID()
        suggest(a, null); suggest(a, null, relation = LinkRelation.REFUND); suggest(a, null, userId = UUID.randomUUID()); suggest(a, null, kind = LinkKind.REJECTED)
        assertEquals(2, links.findByUser(owner.id, LinkKind.SUGGESTED).size)
        assertEquals(1, links.findByUser(owner.id, LinkKind.SUGGESTED, LinkRelation.REFUND).size)
    }

    // ── Route wire format ──

    @Test
    fun `listed suggestions decode with the published serializer`() = routeTest({ suggestionRoutes(service) }) {
        val a = saved(TransactionType.DEBIT); val b = saved(TransactionType.DEBIT)
        suggest(a.id, b.id)
        val data = Json.parseToJsonElement(client.get("/api/v1/transactions/suggestions") { bearerAuth(tokenFor(owner)) }.bodyAsText()).jsonObject["data"]!!
        val list = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(ListSerializer(SuggestionResponse.serializer()), data)
        assertEquals("duplicate", list.single().relation)
        assertRejectsMissingFields(SuggestionResponse.serializer())
    }
}
