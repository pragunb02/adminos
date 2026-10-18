package dev.adminos.api.di

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.wealth.InMemoryInstrumentRepository
import dev.adminos.api.domain.wealth.InMemoryInvestmentAccountRepository
import dev.adminos.api.domain.wealth.InMemoryInvestmentLedger
import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.InvestmentAccountRepository
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.WealthAudit
import dev.adminos.api.domain.wealth.imports.CasImportService
import dev.adminos.api.domain.wealth.imports.CasReader
import dev.adminos.api.domain.wealth.imports.HoldingCheckRepository
import dev.adminos.api.domain.wealth.imports.InMemoryHoldingCheckRepository
import dev.adminos.api.domain.wealth.imports.ReconciliationService
import dev.adminos.api.infrastructure.crypto.WealthIdentifiers
import dev.adminos.api.infrastructure.database.repositories.ExposedHoldingCheckRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedInstrumentRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedInvestmentAccountRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedInvestmentLedger
import dev.adminos.api.infrastructure.statements.HttpCasReader
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.wealth.flows.InvestmentFlowRules
import dev.adminos.api.domain.wealth.flows.InvestmentFlowService
import dev.adminos.api.domain.wealth.lots.LotService
import dev.adminos.api.domain.wealth.prices.AmfiNavJob
import dev.adminos.api.domain.wealth.prices.InMemoryPriceRepository
import dev.adminos.api.domain.wealth.prices.NavFileSource
import dev.adminos.api.domain.wealth.prices.PriceRepository
import dev.adminos.api.domain.wealth.prices.PriceService
import dev.adminos.api.infrastructure.database.repositories.ExposedPriceRepository
import dev.adminos.api.infrastructure.prices.HttpAmfiNavSource
import org.koin.core.module.Module
import org.koin.dsl.bind
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
    single<HoldingCheckRepository> { if (useDatabase) ExposedHoldingCheckRepository() else InMemoryHoldingCheckRepository(get()) }

    // Prices (AOS-810): AMFI's whole-market NAV file once a day, plus each statement's own valuation NAV.
    single<PriceRepository> { if (useDatabase) ExposedPriceRepository() else InMemoryPriceRepository() }
    single<NavFileSource> { HttpAmfiNavSource() }
    single { PriceService(get(), get(), get(), get()) }
    single { AmfiNavJob(get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class

    // Investment flows in the bank feed (AOS-815): the pipeline step's rules (bad data stops start-up) and the matcher.
    single { InvestmentFlowRules.load() }
    single { InvestmentFlowService(get(), get()) }

    // FIFO lots (AOS-811): derived from the ledger on read.
    single { LotService(get()) }

    // CAS import (AOS-805): the parser service reads the file in memory (D-37); the API reconciles and records.
    single<CasReader> { HttpCasReader(get<AppConfig>().statementParser.url) }
    single { ReconciliationService(get(), get()) }
    single { CasImportService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
