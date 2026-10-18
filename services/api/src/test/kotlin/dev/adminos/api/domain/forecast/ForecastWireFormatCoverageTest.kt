package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.balances.AccountBalanceDto
import dev.adminos.api.domain.balances.BalancesResponse
import dev.adminos.api.domain.documents.DocumentDeletedResponse
import dev.adminos.api.domain.documents.DocumentPatchRequest
import dev.adminos.api.domain.documents.DocumentResponse
import dev.adminos.api.domain.identity.assertWireFormat
import dev.adminos.api.domain.wealth.holdings.GroupDto
import dev.adminos.api.domain.wealth.holdings.HoldingDto
import dev.adminos.api.domain.wealth.holdings.HowDto
import dev.adminos.api.domain.wealth.holdings.ReturnDto
import dev.adminos.api.domain.wealth.holdings.WealthOverviewDto
import dev.adminos.api.domain.wealth.imports.HoldingStatusDto
import kotlin.test.Test

/**
 * The wire format of the forecast (AOS-714/720/721/722), balances (AOS-720), documents (AOS-717) and holdings (AOS-820):
 * every response and request round-trips with only its required fields and with all of them, with and without default
 * encoding, and a body missing a required field is refused. Synthetic values only.
 */
class ForecastWireFormatCoverageTest {

    private val due = DueDto("Tata Power", "2026-10-25", "2350.50", overdue = false)
    private val projected = due.copy(billType = "electricity", monthsSeen = 3, seenFrom = "2026-07")
    private val part = ForecastPartDto("bills", "2350.50", listOf(due), null, null, null, null, null, null, null, null)
    private val everyday = ForecastPartDto("everyday", "10291.00", emptyList(), "550.00", "1060.25", 11, 4, true, "weekly_median", "2026-08-22", "2026-10-16")
    private val balance = ForecastBalanceDto("hdfc", "4321", "52000.00", "2026-10-15", "sms")
    private val until = UntilSalaryDto(null, null, "0.00", "0.00", "0.00", null, null, emptyList())
    private val untilFull = UntilSalaryDto(
        "2026-10-17", "2026-10-29", "5100.00", "10481.25", "36418.75", "33000.00", "41100.00", listOf(part, everyday),
        basis = "balance", spentFrom = "2026-10-15", balance = "52000.00", balanceAsOf = "2026-10-15",
        accounts = listOf(balance), notCounted = listOf(balance.copy(last4 = "8642", reason = "stale")), balanceMaxAgeDays = 7
    )
    private val salary = SalaryDto("unknown", "no_regular_credit", null, null, null, null, null, null, null)
    private val salaryFull = SalaryDto("expected", null, "ACME TECHNOLOGIES", "2026-10-30", "100000.00", "100000.00", "2026-09-30", 6, untilFull,
        source = "confirmed", usualDay = 30)

    @Test
    fun `forecast parts, dues and ranges round-trip`() {
        assertWireFormat(DueDto.serializer(), due, projected, """{"name":"x","date":"2026-10-25","amount":"1.00"}""")
        assertWireFormat(ForecastPartDto.serializer(), part, everyday, """{"kind":"bills"}""")
        assertWireFormat(ForecastRangeDto.serializer(), ForecastRangeDto("1.00", "2.00", 80, 3), ForecastRangeDto("57200.00", "66900.00", 80, 7), """{"low":"1"}""")
        assertWireFormat(ForecastHistoryDto.serializer(), ForecastHistoryDto(40, 90, null), ForecastHistoryDto(40, 90, "2026-11-25"), """{"days":40}""")
        assertWireFormat(MonthEndDto.serializer(), MonthEndDto("2026-10", "0.00", "0.00", 0, emptyList(), null, 3),
            MonthEndDto("2026-10", "48250.00", "61540.50", 15, listOf(part, everyday), ForecastRangeDto("57200.00", "66900.00", 80, 7), 0), """{"month":"2026-10"}""")
    }

    @Test
    fun `money left, balances and the salary round-trip`() {
        assertWireFormat(ForecastBalanceDto.serializer(), balance, balance.copy(bank = null, source = "statement", reason = "before_salary"), """{"last4":"4321"}""")
        assertWireFormat(UntilSalaryDto.serializer(), until, untilFull, """{"spentSince":"0.00"}""")
        assertWireFormat(SalaryDto.serializer(), salary, salaryFull, """{"status":"expected"}""")
        assertWireFormat(ForecastResponse.serializer(), ForecastResponse("not_enough_history", "2026-10-16", ForecastHistoryDto(40, 90, "2026-11-25"), null, null),
            ForecastResponse("ready", "2026-10-16", null, MonthEndDto("2026-10", "48250.00", "61540.50", 15, listOf(part), null, 0), salaryFull), """{"status":"ready"}""")
    }

    @Test
    fun `salary choices round-trip`() {
        assertWireFormat(SalaryChoiceRequest.serializer(), SalaryChoiceRequest("confirm"), SalaryChoiceRequest("correct", "ACME", 30, "100000.00"), """{"payer":"ACME"}""")
        val detected = DetectedSalaryDto(null, 30, "100000.00", "100000.00", "2026-09-30", "2026-10-30", 6)
        val choice = SalaryChoiceDto(null, 30, "100000.00", "confirmed", "2026-10-16T05:00:00Z")
        assertWireFormat(DetectedSalaryDto.serializer(), detected, detected.copy(payer = "ACME"), """{"usualDay":30}""")
        assertWireFormat(SalaryChoiceDto.serializer(), choice, choice.copy(payer = "ACME", kind = "corrected"), """{"day":30}""")
        assertWireFormat(SalaryChoiceResponse.serializer(), SalaryChoiceResponse(null, "no_regular_credit", null), SalaryChoiceResponse(detected, null, choice), """{"detected":null}""")
    }

    @Test
    fun `bank balances round-trip`() {
        val a = AccountBalanceDto(null, "4321", "-120.00", "2026-10-15T05:00:00Z", "2026-10-15", "statement", 9, "stale", false)
        assertWireFormat(AccountBalanceDto.serializer(), a, a.copy(bank = "hdfc", amount = "52000.00", source = "sms", ageDays = 1, freshness = "fresh", counted = true), """{"last4":"4321"}""")
        assertWireFormat(BalancesResponse.serializer(), BalancesResponse("2026-10-16", emptyList(), "0.00", 0, 7), BalancesResponse("2026-10-16", listOf(a), "52000.00", 1, 7), """{"today":"2026-10-16"}""")
    }

    @Test
    fun `documents round-trip`() {
        val d = DocumentResponse("d-1", "Form 16", "tax", "pdf", "application/pdf", 245_760, null, null, null, "2026-10-16T06:00:00Z")
        assertWireFormat(DocumentResponse.serializer(), d, d.copy(originalName = "Form16.pdf", expiresOn = "2030-03-05", notes = "n", issuer = "Example Bank",
            numberLast4 = "A1B2", amount = "12500.00"), """{"id":"d-1"}""")
        assertWireFormat(DocumentPatchRequest.serializer(), DocumentPatchRequest(), DocumentPatchRequest("t", "tax", "", "", "Example Bank", "A1B2", "12500.00"), null)
        assertWireFormat(DocumentDeletedResponse.serializer(), DocumentDeletedResponse(false), DocumentDeletedResponse(true), "{}")
    }

    @Test
    fun `holdings round-trip`() {
        val ret = ReturnDto("absolute", null, 30, "under_a_year")
        val how = HowDto("10.000", null, null, null, false, null, 1, 1, "fifo", emptyList())
        val h = HoldingDto("Example Fund - Growth", "XXXX1234", "Example MF", null, null, "1000.00", "1000.00", "0.00", null, "0.00", "0.00", true, ret, emptyList(), how)
        val hFull = h.copy(plan = "direct", isin = "INF000000001", value = "1100.00", gain = "100.00", gainPercent = "10.0", valuedAtCost = false,
            ret = ret.copy(basis = "xirr", percent = "12.3", days = 400, reason = null), issues = listOf("stale_price"),
            how = how.copy(price = "110.00", priceDate = "2026-10-15", priceSource = "AMFI daily NAV file", stale = true, staleAfterDays = 3, steps = listOf("a")))
        assertWireFormat(ReturnDto.serializer(), ret, hFull.ret, """{"basis":"xirr"}""")
        assertWireFormat(HowDto.serializer(), how, hFull.how, """{"units":"1"}""")
        assertWireFormat(HoldingDto.serializer(), h, hFull, """{"scheme":"x"}""")
        val g = GroupDto("equity_in", "1000.00", "1000.00", emptyList())
        assertWireFormat(GroupDto.serializer(), g, g.copy(holdings = listOf(hFull)), """{"assetClass":"equity_in"}""")
        val status = HoldingStatusDto("XXXX1234", "Example MF", "Example Fund", "ok", null, "2026-10-15", "10", "10")
        val o = WealthOverviewDto("2026-10-16", null, 0, 0, "0.00", "0.00", "0.00", null, "0.00", ret, emptyList(), emptyList())
        assertWireFormat(WealthOverviewDto.serializer(), o, o.copy(pricesAsOf = "2026-10-15", stale = 1, valuedAtCost = 1, gainPercent = "1.0", groups = listOf(g),
            needsReview = listOf(status.copy(status = "blocked", reason = "units_differ"))), """{"asOf":"2026-10-16"}""")
    }
}
