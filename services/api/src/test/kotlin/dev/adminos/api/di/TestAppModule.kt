package dev.adminos.api.di

import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
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

    // Services
    single { AuditService(get(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())) }
    single { AuthService(get(), get(), get(), get(), get<AppConfig>().auth) }
    single { IngestionService(IngestEventService(InMemoryIngestEventRepository()), get()) }
    single { ConnectionService(get(), get(), get(), get()) }
    single { GmailCronService(get(), get(), get()) }
    single<CategoryRuleRepository> { InMemoryCategoryRuleRepository() }
    single { CategorizationService(get()) }
    single { CategoryCorrectionService(get(), get()) }
    single { AccountDiscoveryService(get(), get()) }
    single<PriceRiseRepository> { InMemoryPriceRiseRepository() }
    single<dev.adminos.api.domain.financial.grouping.MerchantAliasRepository> { dev.adminos.api.domain.financial.grouping.InMemoryMerchantAliasRepository() }
    single { SubscriptionDetectorService(get(), get(), get(), get(), get()) }
    single { BillTrackingService(get(), get()) }
    single { AnomalyDetectorService(get(), get(), get()) }
    single { dev.adminos.api.domain.notifications.delivery.NotificationDispatcher(listOf(dev.adminos.api.domain.notifications.delivery.InAppChannel()), get(), get()) }
    single { NotificationService(get(), get(), get()) }
    single<dev.adminos.api.domain.notifications.push.PushSubscriptionRepository> { dev.adminos.api.domain.notifications.push.InMemoryPushSubscriptionRepository() }
    single { dev.adminos.api.domain.notifications.push.PushSubscriptionService(get(), null, null) }
    single { dev.adminos.api.domain.identity.UserCalendar(get()) }
    single { ReminderEngine(get(), get(), get(), get()) }
    single { DropOffNudgeService(get(), get(), get()) }
}
