package dev.adminos.api.di

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.audit.AuditRepository
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.identity.*
import dev.adminos.api.domain.ingestion.connection.ConnectionRepository
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.InMemoryConnectionRepository
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSessionRepository
import dev.adminos.api.domain.ingestion.webhook.GmailCronService
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import org.koin.dsl.module

val appModule = module {
    single { AppConfig.load() }
    single { TokenEncryptor(get<AppConfig>().security.tokenEncryptionKey) }
    single { JwtService(get<AppConfig>().auth) }
    single { GoogleOAuthClient(get<AppConfig>().auth) }

    // Repositories
    single<UserRepository> { InMemoryUserRepository() }
    single<SessionRepository> { InMemorySessionRepository() }
    single<ConnectionRepository> { InMemoryConnectionRepository() }
    single<SyncSessionRepository> { InMemorySyncSessionRepository() }
    single<AuditRepository> { InMemoryAuditRepository() }

    // Financial Repositories
    single<TransactionRepository> { InMemoryTransactionRepository() }
    single<AccountRepository> { InMemoryAccountRepository() }
    single<SubscriptionRepository> { InMemorySubscriptionRepository() }
    single<BillRepository> { InMemoryBillRepository() }

    // Services
    single { AuditService(get(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())) }
    single { AuthService(get(), get(), get(), get(), get<AppConfig>().auth) }
    single { IngestionService(get()) }
    single { ConnectionService(get(), get(), get()) }
    single { GmailCronService(get(), get()) }

    // Financial Services
    single { CategorizationService() }
    single { AccountDiscoveryService(get(), get()) }
    single { SubscriptionDetectorService(get(), get()) }
    single { BillTrackingService(get(), get()) }

    // Agent Repositories
    single<AnomalyRepository> { InMemoryAnomalyRepository() }
    single<BriefingRepository> { InMemoryBriefingRepository() }
    single<InsightRepository> { InMemoryInsightRepository() }

    // Agent Services
    single { AnomalyDetectorService(get(), get()) }
}
