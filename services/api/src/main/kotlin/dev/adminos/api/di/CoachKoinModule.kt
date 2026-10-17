package dev.adminos.api.di

import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.lenses.InMemoryLensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.domain.coach.limits.*
import dev.adminos.api.domain.coach.settings.CoachSettingsRepository
import dev.adminos.api.domain.coach.settings.CoachSettingsService
import dev.adminos.api.domain.coach.settings.InMemoryCoachSettingsRepository
import dev.adminos.api.domain.ingestion.pipeline.TransactionCreatedListener
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.infrastructure.database.repositories.*
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The spending coach's wiring (Epic E9, D-80), kept in its own file so the coach's tasks don't all edit
 * the shared [buildAppModule]: it is included there with one line.
 */
internal fun coachKoinModule(useDatabase: Boolean): Module = module {
    // Persistence: Postgres with a database, memory in dev mode (like AppModule).
    single<LensOverrideRepository> { if (useDatabase) ExposedLensOverrideRepository() else InMemoryLensOverrideRepository() }
    single<SpendingLimitRepository> { if (useDatabase) ExposedSpendingLimitRepository() else InMemorySpendingLimitRepository() }
    single<LimitAlertLedger> { if (useDatabase) ExposedLimitAlertLedger() else InMemoryLimitAlertLedger() }
    single<SuggestionDismissals> { if (useDatabase) ExposedSuggestionDismissals() else InMemorySuggestionDismissals() }
    single<CoachSettingsRepository> { if (useDatabase) ExposedCoachSettingsRepository() else InMemoryCoachSettingsRepository() }
    single<SpendHistory> { if (useDatabase) ExposedSpendHistory() else RepositorySpendHistory(get()) }

    // Habit lenses (AOS-901) and leaks (AOS-902)
    single { LensMatcher() }
    single { LensService(get(), get()) }
    single { LeakService(get(), get()) }
    single { CoachSettingsService(get()) }

    // Limits (AOS-904): checked after each new payment and every 10 minutes
    single { LimitService(get(), get(), get(), get(), get()) }
    single { LimitAlertService(get(), get(), get(), get(), get()) }
    single { LimitAlertListener(get()) } bind TransactionCreatedListener::class
    single { LimitAlertsJob(get(), get()) } bind ScheduledJob::class
}
