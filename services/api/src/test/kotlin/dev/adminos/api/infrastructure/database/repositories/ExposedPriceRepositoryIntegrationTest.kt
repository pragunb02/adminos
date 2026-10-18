package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.wealth.prices.PriceRepositoryContract
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/** AOS-810 on Postgres: the price contract (batch upsert, DISTINCT ON latest, AMFI over statement). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedPriceRepositoryIntegrationTest : PriceRepositoryContract() {
    override val prices = ExposedPriceRepository()
    override val instruments = ExposedInstrumentRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()
}
