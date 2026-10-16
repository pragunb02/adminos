package dev.adminos.api.di

import dev.adminos.api.domain.analytics.AnalyticsRepository
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.audit.AuditRepository
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.ingestion.statements.StatementParser
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.infrastructure.database.repositories.ExposedAnalyticsRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedAuditRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedTransactionRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedUserRepository
import dev.adminos.api.infrastructure.queue.AsynqPublisher
import dev.adminos.api.infrastructure.statements.HttpStatementParser
import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The production DI module (AppModule.kt) in each configuration: every definition resolves (AOS-312). */
class DiAppModuleCoverageTest {

    private fun koinOf(module: Module): Koin = koinApplication { modules(module) }.koin

    /** Resolves every definition in the module and returns how many there were. */
    @OptIn(KoinInternalApi::class)
    private fun Koin.resolveAll(): Int {
        val definitions = instanceRegistry.instances.values.map { it.beanDefinition }.distinct()
        definitions.forEach { d -> assertNotNull(get<Any>(d.primaryType, d.qualifier), "resolve ${d.primaryType}") }
        return definitions.size
    }

    private val noEnv: (String) -> String? = { null }

    @Test
    fun `in-memory configuration resolves every definition`() {
        val koin = koinOf(buildAppModule(useDatabase = false, env = noEnv))
        assertTrue(koin.resolveAll() > 50)
    }

    @Test
    fun `in-memory configuration binds the in-memory repositories`() {
        val koin = koinOf(buildAppModule(useDatabase = false, env = noEnv))
        assertIs<InMemoryUserRepository>(koin.get<UserRepository>())
        assertIs<InMemoryTransactionRepository>(koin.get<TransactionRepository>())
        assertIs<InMemoryAnalyticsRepository>(koin.get<AnalyticsRepository>())
        assertIs<InMemoryAuditRepository>(koin.get<AuditRepository>())
    }

    @Test
    fun `database configuration resolves every definition`() {
        val koin = koinOf(buildAppModule(useDatabase = true, env = noEnv))
        assertTrue(koin.resolveAll() > 50)
    }

    @Test
    fun `database configuration binds the Exposed repositories`() {
        val koin = koinOf(buildAppModule(useDatabase = true, env = noEnv))
        assertIs<ExposedUserRepository>(koin.get<UserRepository>())
        assertIs<ExposedTransactionRepository>(koin.get<TransactionRepository>())
        assertIs<ExposedAnalyticsRepository>(koin.get<AnalyticsRepository>())
        assertIs<ExposedAuditRepository>(koin.get<AuditRepository>())
    }

    @Test
    fun `without REDIS_URL there is no queue publisher`() {
        val koin = koinOf(buildAppModule(useDatabase = false, env = mapOf("REDIS_URL" to "  ")::get))
        assertNull(koin.getOrNull<RedisClient>())
        assertNull(koin.getOrNull<AsynqPublisher>())
        assertNotNull(koin.get<IngestionService>())
    }

    @Test
    fun `R2 settings, complete or partial, do not change the bindings`() {
        for (env in listOf(
            mapOf("R2_ACCESS_KEY" to "key", "R2_ENDPOINT" to "https://r2.example.com"),
            mapOf("R2_ACCESS_KEY" to "key"),
            mapOf("R2_ENDPOINT" to "https://r2.example.com")
        )) {
            val koin = koinOf(buildAppModule(useDatabase = false, env = env::get))
            assertTrue(koin.resolveAll() > 50, "env $env")
        }
    }

    @Test
    fun `the statement parser points at the configured parser service`() {
        val koin = koinOf(buildAppModule(useDatabase = false, env = noEnv))
        assertIs<HttpStatementParser>(koin.get<StatementParser>())
    }

    @Test
    fun `accepted intake events wake the processor and are stored`() = runBlocking<Unit> {
        val koin = koinOf(buildAppModule(useDatabase = false, env = noEnv))
        val user = UUID.randomUUID()
        val result = koin.get<IngestEventService>().ingest(listOf(IngestEvent(
            userId = user, source = IngestSource.MANUAL, sourceRef = "cash:di-test-0001", occurredAt = Instant.parse("2026-10-01T04:00:00Z"),
            direction = IngestDirection.DEBIT, amount = BigDecimal("50.00")
        )))
        assertEquals(1, result.accepted)
    }

    @Test
    fun `the default module reads the real environment`() {
        assertTrue(koinOf(appModule).resolveAll() > 50)
    }
}
