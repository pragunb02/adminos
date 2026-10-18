package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** What detection alone sees ([detected], without the owner's choice), and the owner's [choice] if any (AOS-722). */
data class SalaryChoiceView(val detected: SalaryFinding, val choice: SalaryChoice?)

/** The owner's correction: who pays the salary, on which day, how much. */
data class SalaryCorrection(val payer: String?, val day: Int, val amount: BigDecimal)

sealed interface SalaryChoiceOutcome {
    data class Saved(val view: SalaryChoiceView) : SalaryChoiceOutcome
    /** Confirm needs a detected salary; detection found none, for [reason]. */
    data class NothingToConfirm(val reason: NoSalaryReason) : SalaryChoiceOutcome
    /** The detected payer's name can't be stored as a choice (too long, or invisible characters): correct it instead. */
    data object NotStorable : SalaryChoiceOutcome
}

/**
 * Confirms or corrects the detected salary (AOS-722). Confirm stores exactly what detection found (the client sends
 * no values); correct stores the owner's payer, day and amount. One choice per user, the latest wins.
 */
class SalaryChoiceService(
    private val choices: SalaryChoiceRepository,
    private val history: ForecastHistoryRepository,
    private val detector: SalaryDetector = SalaryDetector(),
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun view(userId: UUID, zone: ZoneId): SalaryChoiceView =
        SalaryChoiceView(detectAlone(userId, zone), choices.find(userId))

    suspend fun confirm(userId: UUID, zone: ZoneId): SalaryChoiceOutcome {
        val detected = detectAlone(userId, zone)
        val s = when (detected) {
            is SalaryFinding.NotFound -> return SalaryChoiceOutcome.NothingToConfirm(detected.reason)
            is SalaryFinding.Found -> detected.salary
        }
        // A bank's payer name can be longer than a choice allows or carry invisible characters: then only a correction can store it.
        val choice = try {
            SalaryChoice.of(s.payer, s.usualDay, s.typicalAmount, SalaryChoiceKind.CONFIRMED, clock.instant())
        } catch (_: IllegalArgumentException) {
            return SalaryChoiceOutcome.NotStorable
        }
        return SalaryChoiceOutcome.Saved(SalaryChoiceView(detected, choices.put(userId, choice)))
    }

    /** @throws IllegalArgumentException when the payer, day or amount isn't valid (see [SalaryChoice]). */
    suspend fun correct(userId: UUID, zone: ZoneId, correction: SalaryCorrection): SalaryChoiceOutcome {
        val choice = SalaryChoice.of(correction.payer, correction.day, correction.amount, SalaryChoiceKind.CORRECTED, clock.instant())
        val saved = choices.put(userId, choice)
        return SalaryChoiceOutcome.Saved(SalaryChoiceView(detectAlone(userId, zone), saved))
    }

    /** Detection over the same income window as the forecast, ignoring any stored choice. */
    private suspend fun detectAlone(userId: UUID, zone: ZoneId): SalaryFinding {
        val today = LocalDate.now(clock.withZone(zone))
        val incomes = history.incomes(userId, DateRange(today.minusDays(ForecastService.INCOME_DAYS), today), zone)
        return detector.detect(incomes, today)
    }
}
