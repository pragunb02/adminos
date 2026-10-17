package dev.adminos.api.di

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.wealth.InMemoryInstrumentRepository
import dev.adminos.api.domain.wealth.InMemoryInvestmentAccountRepository
import dev.adminos.api.domain.wealth.InMemoryInvestmentLedger
import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.InvestmentAccountRepository
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.WealthAudit
import dev.adminos.api.infrastructure.crypto.WealthIdentifiers
import dev.adminos.api.infrastructure.database.repositories.ExposedInstrumentRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedInvestmentAccountRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedInvestmentLedger
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Wealth wiring (Epic E8, D-79), in its own file so the wealth tasks don't all edit [buildAppModule]:
 * it is included there with one line.
 */
internal fun wealthKoinModule(useDatabase: Boolean): Module = module {
    // Identifiers are encrypted under a key derived for wealth only (AOS-802); a bad TOKEN_ENCRYPTION_KEY stops start-up.
    single { WealthIdentifiers(get<AppConfig>().security.tokenEncryptionKey) }
    single { WealthAudit(get()) }

    // Persistence: Postgres with a database, memory in dev mode (like AppModule).
    single<InvestmentAccountRepository> { if (useDatabase) ExposedInvestmentAccountRepository() else InMemoryInvestmentAccountRepository() }
    single<InstrumentRepository> { if (useDatabase) ExposedInstrumentRepository() else InMemoryInstrumentRepository() }
    single<InvestmentLedger> { if (useDatabase) ExposedInvestmentLedger() else InMemoryInvestmentLedger() }
}
