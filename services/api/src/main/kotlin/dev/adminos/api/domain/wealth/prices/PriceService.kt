package dev.adminos.api.domain.wealth.prices

import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.wealth.Instrument
import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.WealthAudit
import dev.adminos.api.domain.wealth.WealthAuditActions
import dev.adminos.api.domain.wealth.imports.HoldingCheck
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The whole-market NAV file (AOS-810). A bulk download, so AMFI never learns which schemes are held (D-79). */
fun interface NavFileSource {
    /** @throws PriceFetchException when the file can't be fetched; never returns a partial file */
    suspend fun download(): String
}

class PriceFetchException(message: String) : RuntimeException(message)

/** What one AMFI fetch did; [runId] is the audit entity of the fetch. */
data class AmfiRefresh(val runId: UUID, val rowsInFile: Int, val skippedRows: Int, val instruments: Int, val stored: Int, val unmatched: Int)

/**
 * Prices for holdings (AOS-810, D-79):
 * - [refreshFromAmfi] downloads AMFI's daily file, keeps only the NAVs of instruments we know (matched by ISIN, so the
 *   exact plan and option; else by AMFI scheme code) and upserts them;
 * - [recordStatementNavs] keeps the NAV a statement printed, as a fallback until the next AMFI file;
 * - [priceAt] values on a date with the latest price on or before it, labelled stale past the instrument's limit.
 */
class PriceService(
    private val source: NavFileSource,
    private val instruments: InstrumentRepository,
    private val prices: PriceRepository,
    private val audit: WealthAudit,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(PriceService::class.java)

    suspend fun refreshFromAmfi(): AmfiRefresh {
        val known = instruments.mutualFunds()
        val file = AmfiNavParser.parse(source.download())
        val byIsin = HashMap<String, AmfiNav>()
        val byCode = HashMap<String, AmfiNav>()
        for (nav in file.navs) {
            nav.isins.forEach { byIsin[it] = nav }
            byCode[nav.schemeCode] = nav
        }
        val now = clock.instant()
        val matched = known.mapNotNull { i -> match(i, byIsin, byCode)?.let { PriceObservation(i.id, it.date, it.nav, PriceSource.AMFI, now) } }
        val stored = prices.upsert(matched)
        val result = AmfiRefresh(UUID.randomUUID(), file.navs.size, file.skipped, known.size, stored, known.size - matched.size)
        audit.record(null, WealthAuditActions.PRICE_FETCH, "price_fetch", result.runId,
            counts = mapOf("rows" to result.rowsInFile, "skipped" to result.skippedRows, "instruments" to result.instruments, "stored" to stored, "unmatched" to result.unmatched),
            codes = mapOf("source" to PriceSource.AMFI.wire))
        log.info("AMFI NAVs: {} rows ({} skipped), {} of {} instruments priced", result.rowsInFile, result.skippedRows, stored, known.size)
        return result
    }

    private fun match(i: Instrument, byIsin: Map<String, AmfiNav>, byCode: Map<String, AmfiNav>): AmfiNav? =
        i.isin?.let(byIsin::get) ?: i.amfiCode?.let(byCode::get)

    /** The valuation NAVs printed on an import's statement, as prices of that date (source: statement). */
    suspend fun recordStatementNavs(checks: List<HoldingCheck>): Int {
        val now = clock.instant()
        return prices.upsert(checks.mapNotNull { c ->
            val instrument = c.instrumentId ?: return@mapNotNull null
            val nav = c.valuationNav ?: return@mapNotNull null
            PriceObservation(instrument, c.valuationDate ?: c.statementTo, nav, PriceSource.STATEMENT, now)
        }.distinctBy { it.instrumentId to it.date })
    }

    /** For each instrument, its price for a valuation on [asOf]; instruments with no price at all are absent ("valued at cost"). */
    suspend fun priceAt(instruments: Collection<Instrument>, asOf: LocalDate): Map<UUID, PricedAt> {
        val latest = prices.latestOnOrBefore(instruments.map { it.id }, asOf)
        return instruments.mapNotNull { i -> latest[i.id]?.let { i.id to PricedAt(it, asOf, Staleness.limitDays(i)) } }.toMap()
    }
}

/** Daily after AMFI publishes (late evening India time), one slot a day, idempotent (D-50). */
class AmfiNavJob(private val prices: PriceService, zone: ZoneId) : ScheduledJob {
    override val name = JobName("wealth-amfi-nav")
    override val schedule = Schedule.Daily(LocalTime.of(23, 30), zone)
    override val timeout: Duration = Duration.ofMinutes(5)

    override suspend fun run(): JobResult {
        val r = prices.refreshFromAmfi()
        return JobResult.of("rows" to r.rowsInFile, "skipped" to r.skippedRows, "instruments" to r.instruments, "stored" to r.stored, "unmatched" to r.unmatched)
    }
}
