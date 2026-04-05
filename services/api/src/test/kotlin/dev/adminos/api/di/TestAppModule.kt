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
import dev.adminos.api.domain.notifications.*
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.storage.StorageClient
import dev.adminos.api.infrastructure.storage.StubStorageClient
import org.koin.dsl.module

/**
 * Test module that always binds InMemory implementations regardless of env vars.
 * Use this in tests to avoid requiring PostgreSQL or Redis.
 */
val testAppModule = module {
    single { AppConfig.load() }
    single { TokenEncryptor(get<AppConfig>().security.tokenEncryptionKey) }
    single { JwtService(get<AppConfig>().auth) }
    single { GoogleOAuthClient(get<AppConfig>().auth) }

    // Always InMemory — no env var check
    single<UserRepository> { InMemoryUserRepository() }
    single<SessionRepository> { InMemorySessionRepository() }
    single<DeviceRepository> { InMemoryDeviceRepository() }
    single<ConnectionRepository> { InMemoryConnectionRepository() }
    single<SyncSessionRepository> { InMemorySyncSessionRepository() }
    single<TransactionRepository> { InMemoryTransactionRepository() }
    single<AccountRepository> { InMemoryAccountRepository() }
    single<SubscriptionRepository> { InMemorySubscriptionRepository() }
    single<BillRepository> { InMemoryBillRepository() }
    single<AnomalyRepository> { InMemoryAnomalyRepository() }
    single<BriefingRepository> { InMemoryBriefingRepository() }
    single<InsightRepository> { InMemoryInsightRepository() }
    single<NotificationPreferencesRepository> { InMemoryNotificationPreferencesRepository() }
    single<NotificationRepository> { InMemoryNotificationRepository() }
    single<AuditRepository> { InMemoryAuditRepository() }

    // Storage — always stub in tests
    single<StorageClient> { StubStorageClient() }

    // Services
    single { AuditService(get(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())) }
    single { AuthService(get(), get(), get(), get(), get<AppConfig>().auth) }
    single { IngestionService(get()) }
    single { ConnectionService(get(), get(), get()) }
    single { GmailCronService(get(), get()) }
    single { CategorizationService() }
    single { AccountDiscoveryService(get(), get()) }
    single { SubscriptionDetectorService(get(), get()) }
    single { BillTrackingService(get(), get()) }
    single { AnomalyDetectorService(get(), get()) }
    single { PushService(get()) }
    single { NotificationService(get(), get(), get()) }
    single { ReminderEngine(get(), get(), get()) }
    single { DropOffNudgeService(get(), get(), get()) }
}
