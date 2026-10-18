package dev.adminos.api.domain.ask

import dev.adminos.api.domain.analytics.DateRange
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** AOS-712: named periods become dates in the user's zone, Monday weeks, never past today. */
class PeriodResolverTest {
    private val sunday = LocalDate.of(2026, 10, 18)
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private fun ok(spec: PeriodSpec, today: LocalDate = sunday) = (PeriodResolver.resolve(spec, today) as ResolvedPeriod.Ok).range
    private fun of(p: PeriodPreset) = PeriodSpec.of(p)

    @Test
    fun `each preset on a Sunday in October`() {
        assertEquals(DateRange(d(10, 18), d(10, 18)), ok(of(PeriodPreset.TODAY)))
        assertEquals(DateRange(d(10, 17), d(10, 17)), ok(of(PeriodPreset.YESTERDAY)))
        assertEquals(DateRange(d(10, 12), d(10, 18)), ok(of(PeriodPreset.THIS_WEEK)))
        assertEquals(DateRange(d(10, 5), d(10, 11)), ok(of(PeriodPreset.LAST_WEEK)))
        assertEquals(DateRange(d(10, 1), d(10, 18)), ok(of(PeriodPreset.THIS_MONTH)))
        assertEquals(DateRange(d(9, 1), d(9, 30)), ok(of(PeriodPreset.LAST_MONTH)))
        assertEquals(DateRange(d(10, 12), d(10, 18)), ok(of(PeriodPreset.LAST_7_DAYS)))
        assertEquals(DateRange(d(9, 19), d(10, 18)), ok(of(PeriodPreset.LAST_30_DAYS)))
        assertEquals(DateRange(d(1, 1), d(10, 18)), ok(of(PeriodPreset.THIS_YEAR)))
        assertEquals(DateRange(d(8, 1), d(8, 31)), ok(PeriodSpec.month(YearMonth.of(2026, 8))))
        assertEquals(DateRange(d(10, 5), d(10, 5)), ok(PeriodSpec.range(d(10, 5), d(10, 5))))
    }

    @Test
    fun `on a Monday this week is just today and last week is the seven days before`() {
        val monday = d(10, 19)
        assertEquals(DateRange(monday, monday), ok(of(PeriodPreset.THIS_WEEK), monday))
        assertEquals(DateRange(d(10, 12), d(10, 18)), ok(of(PeriodPreset.LAST_WEEK), monday))
    }

    @Test
    fun `on 1 January last month is the December before and yesterday is last year`() {
        val newYear = d(1, 1, 2027)
        assertEquals(DateRange(d(12, 1), d(12, 31)), ok(of(PeriodPreset.LAST_MONTH), newYear))
        assertEquals(DateRange(d(12, 31), d(12, 31)), ok(of(PeriodPreset.YESTERDAY), newYear))
        assertEquals(DateRange(newYear, newYear), ok(of(PeriodPreset.THIS_YEAR), newYear))
    }

    @Test
    fun `the current month and a range past today end today`() {
        assertEquals(DateRange(d(10, 1), d(10, 18)), ok(PeriodSpec.month(YearMonth.of(2026, 10))))
        assertEquals(DateRange(d(10, 10), d(10, 18)), ok(PeriodSpec.range(d(10, 10), d(10, 31))))
    }

    @Test
    fun `a period that starts after today is future`() {
        assertEquals(ResolvedPeriod.Future, PeriodResolver.resolve(PeriodSpec.month(YearMonth.of(2026, 11)), sunday))
        assertEquals(ResolvedPeriod.Future, PeriodResolver.resolve(PeriodSpec.range(d(10, 19), d(10, 20)), sunday))
    }

    @Test
    fun `a range longer than the analytics allow is too long`() {
        assertEquals(ResolvedPeriod.TooLong, PeriodResolver.resolve(PeriodSpec.range(d(1, 1, 2020), d(10, 1)), sunday))
    }

    @Test
    fun `a spec carries only what its preset needs`() {
        assertFailsWith<IllegalArgumentException> { PeriodSpec(PeriodPreset.TODAY, month = YearMonth.of(2026, 8)) }
        assertFailsWith<IllegalArgumentException> { PeriodSpec(PeriodPreset.MONTH) }
        assertFailsWith<IllegalArgumentException> { PeriodSpec(PeriodPreset.RANGE, from = d(10, 2), to = d(10, 1)) }
        assertFailsWith<IllegalArgumentException> { PeriodSpec(PeriodPreset.RANGE, month = YearMonth.of(2026, 8), from = d(10, 1), to = d(10, 2)) }
        assertEquals(null, PeriodPreset.fromKey("next_week"))
        assertEquals(PeriodPreset.LAST_30_DAYS, PeriodPreset.fromKey("last_30_days"))
    }
}

/** AOS-712: the periods a question names, in English and Hinglish, deterministically. */
class PeriodPhrasesTest {
    private val today = LocalDate.of(2026, 10, 18)
    private fun detect(q: String) = PeriodPhrases.detect(q, today)
    private fun of(p: PeriodPreset) = PeriodSpec.of(p)

    @Test
    fun `aaj and kal are today and yesterday`() {
        assertEquals(listOf(of(PeriodPreset.TODAY)), detect("kitna kharcha hua aaj"))
        assertEquals(listOf(of(PeriodPreset.YESTERDAY)), detect("Kal kitna gaya?"))
        assertEquals(listOf(of(PeriodPreset.YESTERDAY)), detect("What did I spend yesterday"))
    }

    @Test
    fun `Hinglish weeks and months`() {
        assertEquals(listOf(of(PeriodPreset.THIS_WEEK)), detect("Swiggy pe is hafte kitna"))
        assertEquals(listOf(of(PeriodPreset.LAST_WEEK)), detect("pichle hafte ka kharcha"))
        assertEquals(listOf(of(PeriodPreset.THIS_MONTH), of(PeriodPreset.LAST_MONTH)), detect("Is mahine food pe pichle mahine se zyada?"))
        assertEquals(listOf(of(PeriodPreset.THIS_YEAR)), detect("health pe iss saal"))
        assertEquals(listOf(of(PeriodPreset.LAST_7_DAYS)), detect("pichle 7 din mein"))
    }

    @Test
    fun `month names are the latest such month unless a year is given`() {
        assertEquals(listOf(PeriodSpec.month(YearMonth.of(2026, 8))), detect("Blinkit ka August ka kharcha"))
        assertEquals(listOf(PeriodSpec.month(YearMonth.of(2025, 12))), detect("spending in Dec"))
        assertEquals(listOf(PeriodSpec.month(YearMonth.of(2026, 9)), PeriodSpec.month(YearMonth.of(2026, 8))), detect("September compared with Aug"))
        assertEquals(listOf(PeriodSpec.month(YearMonth.of(2025, 7))), detect("Zepto in July 2025"))
    }

    @Test
    fun `may is a month only after in or of or before a year`() {
        assertEquals(emptyList(), detect("May I know my spending?"))
        assertEquals(listOf(PeriodSpec.month(YearMonth.of(2026, 5))), detect("spending in may"))
        assertEquals(listOf(PeriodSpec.month(YearMonth.of(2025, 5))), detect("may 2025 spending"))
    }

    @Test
    fun `words inside other words do not count and fullwidth letters are read`() {
        assertEquals(emptyList(), detect("Kalyan Jewellers total"))
        assertEquals(emptyList(), detect("todays special"))
        assertEquals(listOf(of(PeriodPreset.TODAY)), detect("ｔｏｄａｙ"))
        assertEquals(emptyList(), detect("hmm"))
    }
}
