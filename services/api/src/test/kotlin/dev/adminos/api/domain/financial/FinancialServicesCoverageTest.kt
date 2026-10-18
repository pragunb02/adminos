package dev.adminos.api.domain.financial

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Disabled
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

/** Edge cases of BillTrackingService, categorization, remembered rules and the merchant catalogue (AOS-312). */
class FinancialServicesCoverageTest {

    private val userId = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T04:30:00Z"), ZoneOffset.UTC)
    private val bills = InMemoryBillRepository()
    private val transactions = InMemoryTransactionRepository()
    private val billing = BillTrackingService(bills, transactions, dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository(), clock))
    private val today = LocalDate.of(2026, 10, 16)

    private fun payment(merchant: String?, amount: String) = Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(amount),
        merchantName = merchant, paymentMethod = PaymentMethod.NETBANKING, transactedAt = Instant.parse("2026-10-15T09:00:00Z"))

    // ── BillTrackingService ──

    @Test
    fun `a payment without a merchant name matches no bill`() = runBlocking<Unit> {
        bills.save(Bill(userId = userId, billerName = "Tata Power", amount = BigDecimal("2350.00"), dueDate = today))
        assertNull(billing.matchPayment(payment(null, "2350.00")))
    }

    @Test
    fun `the right amount to a different biller does not mark the bill paid`() = runBlocking<Unit> {
        val bill = bills.save(Bill(userId = userId, billerName = "Tata Power", amount = BigDecimal("2350.00"), dueDate = today))
        assertNull(billing.matchPayment(payment("Zomato", "2350.00")))
        assertEquals(BillStatus.UPCOMING, bills.findById(bill.id)!!.status)
    }

    @Test
    fun `an overdue bill can still be matched by a late payment`() = runBlocking<Unit> {
        bills.save(Bill(userId = userId, billerName = "BESCOM", amount = BigDecimal("1800.00"), dueDate = today.minusDays(5), status = BillStatus.OVERDUE))
        val paid = billing.matchPayment(payment("BESCOM Bangalore", "1800.00"))!!
        assertEquals(BillStatus.PAID, paid.status)
        assertEquals(0, BigDecimal("1800.00").compareTo(paid.paidAmount))
    }
    @Test
    fun `money credited by the biller does not mark the bill paid`() = runBlocking<Unit> {
        val bill = bills.save(Bill(userId = userId, billerName = "Airtel", amount = BigDecimal("599.00"), dueDate = today.plusDays(2)))
        val refund = payment("Airtel", "599.00").copy(type = TransactionType.REFUND, nature = TransactionNature.REFUND)
        assertNull(billing.matchPayment(refund))
        assertEquals(BillStatus.UPCOMING, bills.findById(bill.id)!!.status)
    }

    @Test
    fun `listing and counting bills use the defaults when no filter is given`() = runBlocking<Unit> {
        bills.save(Bill(userId = userId, billerName = "Airtel Postpaid", amount = BigDecimal("599.00"), dueDate = today.plusDays(3)))
        bills.save(Bill(userId = userId, billerName = "Jio Fiber", amount = BigDecimal("999.00"), dueDate = today.plusDays(40), status = BillStatus.PAID))
        assertEquals(2, billing.getUserBills(userId).size)
        assertEquals(2L, billing.countUserBills(userId))
        assertEquals(listOf("Airtel Postpaid"), billing.getUpcomingBills(userId).map { it.billerName })
    }

    @Test
    fun `getBill returns the stored bill or null`() = runBlocking<Unit> {
        val bill = bills.save(Bill(userId = userId, billerName = "SBI Card", amount = BigDecimal("4200.00"), dueDate = today))
        assertEquals(bill, billing.getBill(bill.id))
        assertNull(billing.getBill(UUID.randomUUID()))
    }

    @Test
    fun `jaro-winkler handles identical, empty and disjoint strings`() {
        assertEquals(1.0, BillTrackingService.jaroWinklerSimilarity("airtel", "airtel"))
        assertEquals(0.0, BillTrackingService.jaroWinklerSimilarity("", "airtel"))
        assertEquals(0.0, BillTrackingService.jaroWinklerSimilarity("airtel", ""))
        assertEquals(0.0, BillTrackingService.jaroWinklerSimilarity("abc", "xyz"))
    }

    @Test
    fun `jaro-winkler rewards a shared prefix and counts transpositions`() {
        val prefixed = BillTrackingService.jaroWinklerSimilarity("martha", "marhta")
        assertEquals(0.961, prefixed, 0.001)
        assertEquals(0.867, BillTrackingService.jaroWinklerSimilarity("dixon", "xdion"), 0.001)
    }

    @Test
    fun `the prefix bonus stops at four characters or the shorter string`() {
        assertEquals(0.967, BillTrackingService.jaroWinklerSimilarity("airtel", "airtle"), 0.001)
        assertEquals(0.911, BillTrackingService.jaroWinklerSimilarity("ab", "abc"), 0.001)
    }

    @Test
    fun `fuzzy match is case and space insensitive`() {
        assertEquals(1.0, BillTrackingService.fuzzyMatch("  Tata Power ", "TATA POWER"))
    }

    @Test
    fun `a zero bill matches only a zero payment`() {
        assertTrue(BillTrackingService.isAmountWithinTolerance(BigDecimal.ZERO, BigDecimal.ZERO, 0.05))
        assertFalse(BillTrackingService.isAmountWithinTolerance(BigDecimal.ZERO, BigDecimal("10.00"), 0.05))
    }
    @Test
    fun `a zero bill written with paise matches a zero payment`() {
        assertTrue(BillTrackingService.isAmountWithinTolerance(BigDecimal("0.00"), BigDecimal("0.00"), 0.05))
    }

    // ── CategoryRule / CategorizationService / CategoryCorrectionService ──

    private fun txn(vpa: String?, merchant: String?, owner: UUID = userId) = Transaction(userId = owner, type = TransactionType.DEBIT,
        amount = BigDecimal("80.00"), upiVpa = vpa, merchantName = merchant, transactedAt = Instant.parse("2026-10-15T09:00:00Z"))

    @Test
    fun `a blank UPI ID falls back to the merchant name as the rule key`() {
        assertEquals(RuleMatch.MERCHANT to "ramu tea stall", CategoryRule.keyOf(txn("  ", " Ramu Tea Stall ")))
        assertEquals(RuleMatch.VPA to "ramu@okaxis", CategoryRule.keyOf(txn(" RAMU@okaxis ", "Ramu Tea Stall")))
        assertNull(CategoryRule.keyOf(txn(null, "   ")))
    }

    @Test
    fun `upserting a rule for the same payee replaces its category but keeps its id`() = runBlocking<Unit> {
        val rules = InMemoryCategoryRuleRepository()
        val first = rules.upsert(CategoryRule(userId = userId, match = RuleMatch.VPA, value = "ramu@okaxis", category = TransactionCategory.FOOD))
        val second = rules.upsert(CategoryRule(userId = userId, match = RuleMatch.VPA, value = "ramu@okaxis", category = TransactionCategory.SHOPPING, subcategory = "kirana"))
        assertEquals(first.id, second.id)
        assertEquals(TransactionCategory.SHOPPING, rules.findFor(userId, "ramu@okaxis", null)!!.category)
        assertEquals("kirana", second.subcategory)
    }

    @Test
    fun `a UPI ID without a rule falls back to the merchant rule`() = runBlocking<Unit> {
        val rules = InMemoryCategoryRuleRepository()
        rules.upsert(CategoryRule(userId = userId, match = RuleMatch.MERCHANT, value = "ramu tea stall", category = TransactionCategory.FOOD))
        assertEquals(TransactionCategory.FOOD, rules.findFor(userId, "unknown@ybl", "ramu tea stall")!!.category)
        assertNull(rules.findFor(userId, null, null))
        assertNull(rules.findFor(UUID.randomUUID(), null, "ramu tea stall"))
    }

    @Test
    fun `categorize uses a merchant-name rule when the UPI ID is blank`() = runBlocking<Unit> {
        val rules = InMemoryCategoryRuleRepository()
        rules.upsert(CategoryRule(userId = userId, match = RuleMatch.MERCHANT, value = "ramu tea stall", category = TransactionCategory.FOOD, subcategory = "tea"))
        val result = CategorizationService(rules).categorize(txn(" ", "Ramu Tea Stall"))
        assertEquals(CategorizationService.SOURCE_REMEMBERED, result.source)
        assertEquals("tea", result.subcategory)
    }

    @Test
    fun `categorize with neither UPI ID nor merchant skips the rules and is Other`() = runBlocking<Unit> {
        val result = CategorizationService(InMemoryCategoryRuleRepository()).categorize(txn(" ", " "))
        assertEquals(TransactionCategory.OTHER, result.category)
        assertEquals("rules", result.source)
    }

    @Test
    fun `categorize falls back to the built-in rules when no remembered rule matches`() = runBlocking<Unit> {
        val result = CategorizationService(InMemoryCategoryRuleRepository()).categorize(txn("irctc@sbi", "IRCTC railway ticket"))
        assertEquals(TransactionCategory.TRANSPORT, result.category)
    }

    @Test
    fun `built-in categorization of an unnamed payee is Other`() {
        assertEquals(TransactionCategory.OTHER, CategorizationService().builtIn(null).category)
    }

    @Test
    fun `a correction leaves past transactions that already have that category untouched`() = runBlocking<Unit> {
        val rules = InMemoryCategoryRuleRepository()
        val already = transactions.save(txn("ramu@okaxis", "Ramu").copy(category = TransactionCategory.FOOD, subcategory = "tea"))
        val target = transactions.save(txn("ramu@okaxis", "Ramu"))
        val result = CategoryCorrectionService(rules, transactions).correct(target, TransactionCategory.FOOD, "tea")
        assertEquals(0, result.relabelled)
        assertTrue(result.remembered)
        assertEquals("rules", transactions.findById(already.id)!!.categorySource)
    }

    @Test
    fun `a correction for a payee with no key is not remembered`() = runBlocking<Unit> {
        val target = transactions.save(txn(null, null))
        val result = CategoryCorrectionService(InMemoryCategoryRuleRepository(), transactions).correct(target, TransactionCategory.HEALTH, null)
        assertFalse(result.remembered)
        assertEquals(TransactionCategory.HEALTH, result.transaction.category)
    }

    // ── MerchantCatalogue ──

    @Test
    fun `a blank brand, a duplicate brand and a brand without aliases are rejected`() {
        assertFailsWith<IllegalArgumentException> { MerchantCatalogue("t", listOf(CatalogueMerchant(" ", "food", aliases = listOf("ACME")))) }
        assertFailsWith<IllegalArgumentException> {
            MerchantCatalogue("t", listOf(CatalogueMerchant("Swiggy", "food", aliases = listOf("SWIGGY")), CatalogueMerchant("swiggy", "food", aliases = listOf("BUNDL"))))
        }
        assertFailsWith<IllegalArgumentException> { MerchantCatalogue("t", listOf(CatalogueMerchant("Zepto", "shopping", aliases = emptyList()))) }
    }

    @Test
    fun `an alias repeated within one brand is allowed`() {
        val c = MerchantCatalogue("t", listOf(CatalogueMerchant("Cult.fit", "health", "gym", aliases = listOf("CULT.FIT", "Cult Fit"))))
        assertEquals("Cult.fit", c.aliases["CULT FIT"])
        assertEquals("gym", c.byBrand(" cult.fit ")!!.subcategory)
        assertNull(c.byBrand(null))
        assertNull(c.byBrand("Swiggy"))
    }

    @Test
    fun `a missing catalogue resource fails startup`() {
        assertFailsWith<IllegalStateException> { MerchantCatalogue.load("/merchants/missing.json") }
    }

    @Test
    fun `catalogue entries decode with and without a subcategory`() {
        val withSub = Json.decodeFromString<CatalogueMerchant>("""{"brand":"Swiggy","category":"food","subcategory":"delivery","aliases":["BUNDL TECHNOLOGIES"]}""")
        val withoutSub = Json.decodeFromString<CatalogueMerchant>("""{"brand":"Zepto","category":"shopping","aliases":["ZEPTO"]}""")
        assertEquals("delivery", withSub.subcategory)
        assertNull(withoutSub.subcategory)
        assertEquals(withSub, Json.decodeFromString<CatalogueMerchant>(Json.encodeToString(CatalogueMerchant.serializer(), withSub)))
        assertFailsWith<kotlinx.serialization.SerializationException> { Json.decodeFromString<CatalogueMerchant>("""{"brand":"X"}""") }
    }

    // ── netOfRefunds ──

    @Test
    fun `refunds reduce their category and fully refunded categories drop out`() {
        val net = netOfRefunds(
            mapOf(TransactionCategory.SHOPPING to BigDecimal("999.00"), TransactionCategory.FOOD to BigDecimal("450.00"), TransactionCategory.HEALTH to BigDecimal("300.00")),
            mapOf(TransactionCategory.SHOPPING to BigDecimal("300.00"), TransactionCategory.HEALTH to BigDecimal("300.00"))
        )
        assertEquals(mapOf(TransactionCategory.SHOPPING to BigDecimal("699.00"), TransactionCategory.FOOD to BigDecimal("450.00")), net)
    }
}
