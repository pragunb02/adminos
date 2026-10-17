package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.splits.InMemorySplitRepository
import dev.adminos.api.domain.financial.splits.SplitAction
import dev.adminos.api.domain.financial.splits.SplitEffect
import dev.adminos.api.domain.financial.splits.SplitEntry
import dev.adminos.api.domain.financial.splits.SplitImport
import dev.adminos.api.domain.financial.splits.SplitKind
import dev.adminos.api.domain.financial.splits.SplitRepository
import dev.adminos.api.domain.financial.splits.SplitService
import dev.adminos.api.domain.financial.splits.SplitSource
import dev.adminos.api.domain.financial.splits.SplitState
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.connection.randomTokenEncryptor
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-911 on Postgres: V41, encryption at rest, the one-entry-per-transaction index, and totals that match in-memory. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitwiseIntegrationTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val encryptor = randomTokenEncryptor()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val csv = """
        Date,Description,Category,Cost,Currency,Dev P.,Asha R.,Kabir M.
        2026-10-03,Dinner at Toit,Dining out,4000.00,INR,3000.00,-1500.00,-1500.00
        2026-10-04,Cab to airport,Taxi,900.00,INR,-300.00,600.00,-300.00
        2026-10-05,Payment,Payment,1500.00,INR,-1500.00,1500.00,0.00
        2026-10-06,Their lunch,Dining out,600.00,INR,0.00,300.00,-300.00
        2026-10-07,Late card bill,General,800.00,INR,400.00,-400.00,0.00
    """.trimIndent().toByteArray()

    private fun bank(user: UUID) = listOf(
        Transaction(userId = user, sourceType = "statement", type = TransactionType.DEBIT, amount = BigDecimal("4000.00"),
            merchantName = "Toit Brewpub", category = TransactionCategory.FOOD, paymentMethod = PaymentMethod.CARD_CREDIT,
            transactedAt = LocalDate.of(2026, 10, 3).atTime(21, 0).atZone(ist).toInstant()),
        Transaction(userId = user, sourceType = "sms", type = TransactionType.CREDIT, amount = BigDecimal("1500.00"),
            merchantName = "Asha Rao", paymentMethod = PaymentMethod.UPI,
            transactedAt = LocalDate.of(2026, 10, 5).atTime(10, 0).atZone(ist).toInstant()),
        Transaction(userId = user, sourceType = "sms", type = TransactionType.DEBIT, amount = BigDecimal("250.00"),
            merchantName = "Swiggy", category = TransactionCategory.FOOD,
            transactedAt = LocalDate.of(2026, 10, 6).atTime(13, 0).atZone(ist).toInstant())
    )

    private fun service(repo: SplitRepository, txns: TransactionRepository, db: Boolean) =
        if (db) SplitService(repo, txns, CategorizationService(), UserCalendar(InMemoryUserRepository())) { work -> dbQuery { work() } }
        else SplitService(repo, txns, CategorizationService(), UserCalendar(InMemoryUserRepository()))

    @Test
    fun `the same import gives the same totals on Postgres and in memory, in every view`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val pgTxns = ExposedTransactionRepository()
        val memTxns = InMemoryTransactionRepository()
        bank(user).forEach { pgTxns.save(it); memTxns.save(it) }
        val pgRepo = ExposedSplitRepository(encryptor)
        val pg = service(pgRepo, pgTxns, db = true)
        val mem = service(InMemorySplitRepository(), memTxns, db = false)
        val a = pg.importCsv(user, csv, "Dev P.")
        val b = mem.importCsv(user, csv, "Dev P.")
        assertEquals(a.copy(importId = b.importId), b)
        assertEquals(3, a.applied)

        val sql = AnalyticsService(ExposedAnalyticsRepository())
        val ref = AnalyticsService(InMemoryAnalyticsRepository(memTxns))
        val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16))
        val s = sql.summary(user, oct, ist)
        assertEquals(0, BigDecimal("1550.00").compareTo(s.current.spent), "1000 share + 300 cab + 250 Swiggy")
        assertEquals(0, s.current.received.signum(), "the payback is a settlement, not income")
        assertEquals(0, ref.summary(user, oct, ist).current.spent.compareTo(s.current.spent))
        for (bucket in Bucket.entries) {
            assertEquals(ref.series(user, oct, bucket, ist).map { it.money.spent.stripTrailingZeros() }, sql.series(user, oct, bucket, ist).map { it.money.spent.stripTrailingZeros() })
        }
        assertEquals(ref.categories(user, oct, ist).map { it.category to it.spent.stripTrailingZeros() }, sql.categories(user, oct, ist).map { it.category to it.spent.stripTrailingZeros() })
        assertEquals(0, s.current.spent.compareTo(sql.categories(user, oct, ist).fold(BigDecimal.ZERO) { acc, c -> acc + c.spent }))
        val byCategory = pgTxns.spendingByCategory(user, oct.from.atStartOfDay(ist).toInstant(), oct.to.plusDays(1).atStartOfDay(ist).toInstant())
        assertEquals(0, BigDecimal("1250.00").compareTo(byCategory[TransactionCategory.FOOD]), "the old endpoint counts the share too")

        assertEquals(mem.summary(user).balances.mapValues { it.value.stripTrailingZeros() }, pg.summary(user).balances.mapValues { it.value.stripTrailingZeros() })
        assertEquals(mem.summary(user).counts, pg.summary(user).counts)
        val settled = pgTxns.findByUserId(user, nature = TransactionNature.SETTLEMENT, limit = 10)
        assertEquals(listOf(BigDecimal("1500.00")), settled.map { it.amount }, "the new enum value is stored and filtered")
    }

    @Test
    fun `descriptions and names are encrypted at rest and bound to their row`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val repo = ExposedSplitRepository(encryptor)
        service(repo, ExposedTransactionRepository(), db = true).importCsv(user, csv, "Dev P.")
        val raw = dbQuery {
            TransactionManager.current().exec("SELECT description_enc, coalesce(counterparty_enc, '') AS c FROM split_entries WHERE user_id = ?::uuid",
                listOf(TextColumnType() to user.toString())) { rs -> buildList { while (rs.next()) add(rs.getString(1) + "|" + rs.getString(2)) } }
        }.orEmpty()
        assertEquals(5, raw.size)
        assertTrue(raw.all { it.startsWith("v2:") })
        assertTrue(raw.none { "Asha" in it || "Toit" in it || "Payment" in it })
        assertEquals("Asha R.", repo.findByUser(user).single { it.kind == SplitKind.PAYMENT }.counterparty)
        // A ciphertext copied to another row doesn't decrypt there.
        val (first, second) = repo.findByUser(user).take(2)
        dbQuery {
            TransactionManager.current().exec("UPDATE split_entries SET description_enc = (SELECT description_enc FROM split_entries WHERE id = ?::uuid) WHERE id = ?::uuid",
                listOf(TextColumnType() to first.id.toString(), TextColumnType() to second.id.toString()))
        }
        assertFailsWith<javax.crypto.AEADBadTagException> { repo.findById(second.id) }
        assertEquals(first.description, repo.findById(first.id)?.description)
    }

    @Test
    fun `undo and undoing the import restore Postgres exactly`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val txns = ExposedTransactionRepository()
        val saved = bank(user).map { txns.save(it) }
        val repo = ExposedSplitRepository(encryptor)
        val svc = service(repo, txns, db = true)
        val r = svc.importCsv(user, csv, "Dev P.")
        val settle = repo.findByUser(user, SplitState.APPLIED).single { it.effect == SplitEffect.SETTLE_IN }
        assertEquals(SplitAction.Done, svc.undo(user, settle.id))
        assertEquals(TransactionNature.INCOME, txns.findById(saved[1].id)?.nature)
        assertEquals(setOf(saved[1].id), repo.findById(settle.id)?.rejected)
        assertEquals(SplitAction.Done, svc.undoImport(user, r.importId))
        assertNull(txns.findById(saved[0].id)?.ownShare)
        assertTrue(txns.findByUserId(user, limit = 50).none { it.sourceType == "splitwise" })
        assertTrue(repo.findByUser(user).isEmpty())
        assertTrue(repo.imports(user).isEmpty())
    }

    @Test
    fun `a deleted transaction never fails, and its entry reads as open again`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val txns = ExposedTransactionRepository()
        val saved = bank(user).map { txns.save(it) }
        val repo = ExposedSplitRepository(encryptor)
        service(repo, txns, db = true).importCsv(user, csv, "Dev P.")
        val owed = repo.findByUser(user, SplitState.APPLIED).single { it.effect == SplitEffect.OWED_SPEND }
        txns.delete(saved[0].id)
        txns.delete(owed.transactionId!!)
        val counts = repo.countByState(user)
        assertEquals(SplitState.UNMATCHED, repo.findByUser(user).single { it.description == "Dinner at Toit" }.state)
        assertEquals(SplitState.IGNORED, repo.findById(owed.id)?.state)
        assertEquals(1, repo.findByUser(user, SplitState.IGNORED).size)
        assertEquals(2, repo.findByUser(user, SplitState.UNMATCHED).size, "the dinner and the late card bill")
        assertEquals(2, counts[SplitState.UNMATCHED])
        assertFalse(saved[0].id in repo.heldTransactions(user))
    }

    @Test
    fun `a transaction is split by one entry at most, and a share above the amount is refused`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val txns = ExposedTransactionRepository()
        val debit = txns.save(bank(user)[0])
        val repo = ExposedSplitRepository(encryptor)
        val import = repo.saveImport(SplitImport(userId = user, source = SplitSource.SPLITWISE_CSV, rowsRead = 2))
        fun entry(ref: String) = SplitEntry(userId = user, importId = import.id, source = SplitSource.SPLITWISE_CSV, sourceRef = ref,
            kind = SplitKind.EXPENSE, effect = SplitEffect.ADJUST_DEBIT, state = SplitState.APPLIED, date = LocalDate.of(2026, 10, 3),
            description = "Dinner", counterparty = null, category = null, cost = BigDecimal("4000.00"), currency = "INR",
            ownerNet = BigDecimal("3000.00"), ownerShare = BigDecimal("1000.00"), transactionId = debit.id)
        repo.save(entry("a".repeat(64)))
        assertFailsWith<Exception> { repo.save(entry("b".repeat(64))) }
        assertFailsWith<Exception> { repo.save(entry("a".repeat(64)).copy(id = UUID.randomUUID(), state = SplitState.UNMATCHED, transactionId = null)) }
        assertFailsWith<Exception> { txns.setOwnShare(debit.id, BigDecimal("4000.01")) }
        assertTrue(txns.setOwnShare(debit.id, BigDecimal("1000.00")))
        txns.update(debit.copy(category = TransactionCategory.SHOPPING)) // an older copy without the share
        assertEquals(0, BigDecimal("1000.00").compareTo(txns.findById(debit.id)?.ownShare))
        assertFalse(txns.setOwnShare(UUID.randomUUID(), null))
        assertEquals(1, repo.findBySourceRefs(user, listOf("a".repeat(64), "c".repeat(64))).size)
        assertTrue(repo.findBySourceRefs(user, emptyList()).isEmpty())
        assertEquals(import.id, repo.findImport(import.id)?.id)
    }

    @Test
    fun `a bank record arriving later finds its entry through the listener`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val txns = ExposedTransactionRepository()
        val repo = ExposedSplitRepository(encryptor)
        val svc = service(repo, txns, db = true)
        svc.importCsv(user, csv, "Dev P.")
        val late = txns.save(Transaction(userId = user, sourceType = "statement", type = TransactionType.DEBIT, amount = BigDecimal("800.00"),
            merchantName = "Late Card Bill Co", transactedAt = LocalDate.of(2026, 10, 7).atTime(19, 0).atZone(ist).toInstant()))
        assertEquals(1, repo.findUnmatched(user, BigDecimal("800.00"), LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 9)).size)
        svc.onCreated(late)
        assertEquals(0, BigDecimal("400.00").compareTo(txns.findById(late.id)?.ownShare))
        assertTrue(repo.findUnmatched(user, BigDecimal("800.00"), LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 9)).isEmpty())
    }
}
