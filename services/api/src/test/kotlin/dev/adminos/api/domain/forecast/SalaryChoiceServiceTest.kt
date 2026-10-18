package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** AOS-722: confirm stores what detection found, correct stores the owner's values; one choice per user. */
class SalaryChoiceServiceTest {
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val transactions = InMemoryTransactionRepository()
    private val choices = InMemorySalaryChoiceRepository()
    /** 20 Oct 2026, 10:00 in India. */
    private val now = Instant.parse("2026-10-20T04:30:00Z")
    private val service = SalaryChoiceService(choices, InMemoryForecastHistoryRepository(transactions, InMemoryBillRepository()), clock = Clock.fixed(now, ZoneOffset.UTC))

    private suspend fun salary(day: String, amount: String = "85000", payer: String = "ACME TECHNOLOGIES", owner: UUID = user) =
        transactions.save(Transaction(userId = owner, type = TransactionType.CREDIT, amount = BigDecimal(amount), merchantName = payer,
            transactedAt = LocalDate.parse(day).atTime(9, 0).atZone(ist).toInstant()))

    private suspend fun monthly(vararg days: String = arrayOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01")) = days.forEach { salary(it) }

    @Test
    fun `the view shows what detection alone sees and no choice at first`() = runBlocking<Unit> {
        monthly()
        val view = service.view(user, ist)
        assertEquals("ACME TECHNOLOGIES", assertIs<SalaryFinding.Found>(view.detected).salary.payer)
        assertNull(view.choice)
    }

    @Test
    fun `confirm stores the detected payer, usual day and amount`() = runBlocking<Unit> {
        monthly()
        val saved = assertIs<SalaryChoiceOutcome.Saved>(service.confirm(user, ist))
        val choice = SalaryChoice("ACME TECHNOLOGIES", 1, BigDecimal("85000.00"), SalaryChoiceKind.CONFIRMED, now)
        assertEquals(choice, saved.view.choice)
        assertEquals(choice, choices.find(user))
        assertEquals(choice, service.view(user, ist).choice)
    }

    @Test
    fun `confirm with nothing detected stores nothing and says why`() = runBlocking<Unit> {
        salary("2026-10-01")
        assertEquals(SalaryChoiceOutcome.NothingToConfirm(NoSalaryReason.NO_REGULAR_CREDIT), service.confirm(user, ist))
        assertNull(choices.find(user))
    }

    @Test
    fun `confirm refuses a detected payer name a choice can't hold, so it must be corrected`() = runBlocking<Unit> {
        val long = "ACME " + "X".repeat(120)
        listOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").forEach { salary(it, payer = long) }
        assertEquals(SalaryChoiceOutcome.NotStorable, service.confirm(user, ist))
        assertNull(choices.find(user))
    }

    @Test
    fun `correct stores the owner's payer, day and amount, and the latest answer wins`() = runBlocking<Unit> {
        monthly()
        service.confirm(user, ist)
        val saved = assertIs<SalaryChoiceOutcome.Saved>(service.correct(user, ist, SalaryCorrection("  Globex  ", 15, BigDecimal("40000"))))
        val choice = SalaryChoice("Globex", 15, BigDecimal("40000.00"), SalaryChoiceKind.CORRECTED, now)
        assertEquals(choice, saved.view.choice)
        assertEquals(choice, choices.find(user))
        assertIs<SalaryFinding.Found>(saved.view.detected, "the view still shows what detection alone sees")
    }

    @Test
    fun `correct works when nothing is detected`() = runBlocking<Unit> {
        val saved = assertIs<SalaryChoiceOutcome.Saved>(service.correct(user, ist, SalaryCorrection(null, 31, BigDecimal("50000.50"))))
        assertEquals(NoSalaryReason.NO_REGULAR_CREDIT, assertIs<SalaryFinding.NotFound>(saved.view.detected).reason)
        assertNull(saved.view.choice?.payer)
    }

    @Test
    fun `correct refuses invalid values and keeps the earlier choice`() = runBlocking<Unit> {
        monthly()
        service.confirm(user, ist)
        val before = choices.find(user)
        listOf(SalaryCorrection("ACME", 0, BigDecimal("1")), SalaryCorrection("ACME", 32, BigDecimal("1")), SalaryCorrection("ACME", 1, BigDecimal("0")),
            SalaryCorrection("ACME", 1, BigDecimal("12.345")), SalaryCorrection(" ", 1, BigDecimal("1")), SalaryCorrection("AC\u202EME", 1, BigDecimal("1")),
            SalaryCorrection("A".repeat(101), 1, BigDecimal("1"))).forEach { c ->
            assertFailsWith<IllegalArgumentException> { service.correct(user, ist, c) }
        }
        assertEquals(before, choices.find(user))
    }

    @Test
    fun `one user's choice is never another's`() = runBlocking<Unit> {
        monthly()
        service.confirm(user, ist)
        assertNull(service.view(other, ist).choice)
        assertIs<SalaryFinding.NotFound>(service.view(other, ist).detected, "the other user's detection reads only their own credits")
        service.correct(other, ist, SalaryCorrection("Globex", 15, BigDecimal("40000")))
        assertEquals(SalaryChoiceKind.CONFIRMED, choices.find(user)?.kind)
    }

    @Test
    fun `detection reads only the forecast's income window`() = runBlocking<Unit> {
        // Three salaries more than INCOME_DAYS (200) before today: outside the window, so none is detected.
        listOf("2026-01-01", "2026-02-01", "2026-03-01").forEach { salary(it) }
        assertIs<SalaryFinding.NotFound>(service.view(user, ist).detected)
    }
}
