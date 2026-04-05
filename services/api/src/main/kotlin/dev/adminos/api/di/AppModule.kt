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
import dev.adminos.api.infrastructure.database.repositories.*
import dev.adminos.api.infrastructure.queue.AsynqPublisher
import dev.adminos.api.infrastructure.storage.R2StorageClient
import dev.adminos.api.infrastructure.storage.StorageClient
import dev.adminos.api.infrastructure.storage.StubStorageClient
import io.lettuce.core.RedisClient
import org.koin.dsl.module

val appModule = module {
    single { AppConfig.load() }
    single { TokenEncryptor(get<AppConfig>().security.tokenEncryptionKey) }
    single { JwtService(get<AppConfig>().auth) }
    single { GoogleOAuthClient(get<AppConfig>().auth) }

    // Repository bindings — switch on DATABASE_URL
    val useDatabase = System.getenv("DATABASE_URL")?.isNotBlank() == true

    if (useDatabase) {
        // Identity
        single<UserRepository> { ExposedUserRepository() }
        single<SessionRepository> { ExposedSessionRepository() }
        single<DeviceRepository> { ExposedDeviceRepository() }
        // Ingestion
        single<ConnectionRepository> { ExposedConnectionRepository() }
        single<SyncSessionRepository> { ExposedSyncSessionRepository() }
        // Financial
        single<TransactionRepository> { ExposedTransactionRepository() }
        single<AccountRepository> { ExposedAccountRepository() }
        single<SubscriptionRepository> { ExposedSubscriptionRepository() }
        single<BillRepository> { ExposedBillRepository() }
        // Agent
        single<AnomalyRepository> { ExposedAnomalyRepository() }
        single<BriefingRepository> { ExposedBriefingRepository() }
        single<InsightRepository> { ExposedInsightRepository() }
        // Notifications
        single<NotificationPreferencesRepository> { ExposedNotificationPreferencesRepository() }
        single<NotificationRepository> { ExposedNotificationRepository() }
        // Audit
        single<AuditRepository> { ExposedAuditRepository() }
    } else {
        // InMemory fallback for local dev / testing
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
    }

    // Services — depend on interfaces, unchanged
    single { AuditService(get(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())) }
    single { AuthService(get(), get(), get(), get(), get<AppConfig>().auth) }

    // Redis queue publisher — only if REDIS_URL is configured and not default
    val redisUrl = System.getenv("REDIS_URL") ?: ""
    if (redisUrl.isNotBlank()) {
        single { RedisClient.create(redisUrl) }
        // TODO: Call AsynqPublisher.close() from Ktor ApplicationStopped event
        single { AsynqPublisher(get()) }
    }

    // R2 storage client — real when credentials configured, stub otherwise
    val r2AccessKey = System.getenv("R2_ACCESS_KEY") ?: ""
    val r2Endpoint = System.getenv("R2_ENDPOINT") ?: ""
    if (r2AccessKey.isNotBlank() && r2Endpoint.isNotBlank()) {
        single<StorageClient> { R2StorageClient(get<AppConfig>().r2) }
    } else {
        single<StorageClient> { StubStorageClient() }
    }

    single { IngestionService(get(), getOrNull()) }
    single { ConnectionService(get(), get(), get()) }
    single { GmailCronService(get(), get()) }

    // Financial Services
    single { CategorizationService() }
    single { AccountDiscoveryService(get(), get()) }
    single { SubscriptionDetectorService(get(), get()) }
    single { BillTrackingService(get(), get()) }

    // Agent Services
    single { AnomalyDetectorService(get(), get()) }

    // Notification Services
    single { PushService(get()) }
    single { NotificationService(get(), get(), get()) }
    single { ReminderEngine(get(), get(), get()) }
    single { DropOffNudgeService(get(), get(), get()) }
}
