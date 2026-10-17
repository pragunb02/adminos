package dev.adminos.api.di

import dev.adminos.api.domain.coach.lenses.InMemoryLensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.infrastructure.database.repositories.ExposedLensOverrideRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The spending coach's wiring (Epic E9, D-80), kept in its own file so the coach's tasks don't all edit
 * the shared [buildAppModule]: it is included there with one line.
 */
internal fun coachKoinModule(useDatabase: Boolean): Module = module {
    single<LensOverrideRepository> { if (useDatabase) ExposedLensOverrideRepository() else InMemoryLensOverrideRepository() }
    single { LensMatcher() }
    single { LensService(get(), get()) }
}
