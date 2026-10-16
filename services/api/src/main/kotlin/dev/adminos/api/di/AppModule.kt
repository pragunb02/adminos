package dev.adminos.api.di

import dev.adminos.api.config.AppConfig
import dev.adminos.api.infrastructure.database.repositories.ExposedIngestEventRepository
import dev.adminos.api.infrastructure.database.DatabaseFactory
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
import dev.adminos.api.domain.ingestion.events.IngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository
import dev.adminos.api.domain.financial.links.TransactionLinkRepository
import dev.adminos.api.domain.financial.links.TransactionMatcher
import dev.adminos.api.domain.financial.links.SuggestionService
import dev.adminos.api.infrastructure.database.repositories.ExposedTransactionLinkRepository
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.statements.StatementImportService
import dev.adminos.api.domain.ingestion.statements.StatementParser
import dev.adminos.api.infrastructure.statements.HttpStatementParser
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.domain.identity.DeviceTokenRepository
import dev.adminos.api.domain.identity.DeviceTokenService
import dev.adminos.api.domain.identity.InMemoryDeviceTokenRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedDeviceTokenRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.domain.ingestion.webhook.GmailCronService
import dev.adminos.api.domain.notifications.*
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.database.repositories.*
import dev.adminos.api.infrastructure.queue.AsynqPublisher
import io.lettuce.core.RedisClient
import org.koin.core.module.Module
import org.koin.dsl.bind
import dev.adminos.api.config.SmtpConfig
import dev.adminos.api.config.VapidConfig
import dev.adminos.api.domain.notifications.push.PushSender
import dev.adminos.api.domain.notifications.push.PushSubscriptionService
import dev.adminos.api.domain.notifications.push.WebPushChannel
import dev.adminos.api.infrastructure.push.WebPushSender
import dev.adminos.api.domain.notifications.delivery.*
import dev.adminos.api.infrastructure.email.SmtpEmailSender
import org.koin.dsl.module
import dev.adminos.api.domain.scheduling.*

val appModule = buildAppModule()

/**
 * Builds the DI module. The defaults read the real environment; tests pass [useDatabase] and [env]
 * to build each configuration (testability seam, AOS-312).
 */
internal fun buildAppModule(
    useDatabase: Boolean = DatabaseFactory.isConfigured(),
    env: (String) -> String? = System::getenv
): Module = module {
    single { AppConfig.load() }
    single { TokenEncryptor(get<AppConfig>().security.tokenEncryptionKey) }
    single { JwtService(get<AppConfig>().auth) }
    single { GoogleOAuthClient(get<AppConfig>().auth) }

    // Repository bindings: Exposed when a database is configured, else in-memory (dev mode)
    if (useDatabase) {
        // Identity
        single<UserRepository> { ExposedUserRepository() }
        single<SessionRepository> { ExposedSessionRepository() }
        single<DeviceRepository> { ExposedDeviceRepository() }
        // Ingestion
        single<ConnectionRepository> { ExposedConnectionRepository() }
        single<SyncSessionRepository> { ExposedSyncSessionRepository() }
        single<IngestEventRepository> { ExposedIngestEventRepository() }
        single<DeviceTokenRepository> { ExposedDeviceTokenRepository() }
        single<TransactionLinkRepository> { ExposedTransactionLinkRepository() }
        single<CategoryRuleRepository> { ExposedCategoryRuleRepository() }
        single<dev.adminos.api.domain.analytics.AnalyticsRepository> { ExposedAnalyticsRepository() }
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
        // Scheduling
        single<JobRunRepository> { ExposedJobRunRepository() }
        single<dev.adminos.api.domain.notifications.push.PushSubscriptionRepository> { ExposedPushSubscriptionRepository() }
    } else {
        // InMemory fallback for local dev / testing
        single<UserRepository> { InMemoryUserRepository() }
        single<SessionRepository> { InMemorySessionRepository() }
        single<DeviceRepository> { InMemoryDeviceRepository() }
        single<ConnectionRepository> { InMemoryConnectionRepository() }
        single<SyncSessionRepository> { InMemorySyncSessionRepository() }
        single<IngestEventRepository> { InMemoryIngestEventRepository() }
        single<DeviceTokenRepository> { InMemoryDeviceTokenRepository() }
        single<TransactionLinkRepository> { InMemoryTransactionLinkRepository() }
        single<CategoryRuleRepository> { InMemoryCategoryRuleRepository() }
        single<dev.adminos.api.domain.analytics.AnalyticsRepository> { dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository(get()) }
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
        single<JobRunRepository> { InMemoryJobRunRepository() }
        single<dev.adminos.api.domain.notifications.push.PushSubscriptionRepository> { dev.adminos.api.domain.notifications.push.InMemoryPushSubscriptionRepository() }
    }

    // Services — depend on interfaces, unchanged
    single { AuditService(get(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())) }
    single { AuthService(get(), get(), get(), get(), get<AppConfig>().auth) }

    // Redis queue publisher — only if REDIS_URL is configured and not default
    val redisUrl = env("REDIS_URL") ?: ""
    if (redisUrl.isNotBlank()) {
        single { RedisClient.create(redisUrl) }
        // TODO: Call AsynqPublisher.close() from Ktor ApplicationStopped event
        single { AsynqPublisher(get()) }
    }
    val r2AccessKey = env("R2_ACCESS_KEY") ?: ""
    val r2Endpoint = env("R2_ENDPOINT") ?: ""
    if (r2AccessKey.isNotBlank() && r2Endpoint.isNotBlank()) {
    } else {
    }

    single { IngestionService(get(), get(), getOrNull()) }
    // Ingest pipeline (D-34): pending intake events → transactions
    single { MerchantNormalizer() }
    single { TransactionMatcher() }
    single { dev.adminos.api.domain.financial.links.TransferDetector() }
    single { dev.adminos.api.domain.financial.links.RefundReversalDetector() }
    single {
        if (useDatabase) SuggestionService(get(), get()) { work -> dbQuery { work() } } else SuggestionService(get(), get())
    }
    single<MatchCandidateSource> { RepositoryMatchCandidates(get(), get()) }
    single {
        IngestPipeline(listOf(
            ToTransactionStep(), NormalizeMerchantStep(get()), CrossSourceDedupStep(get(), get()), CategorizeStep(get()),
            TransferStep(get(), get()), RefundReversalStep(get(), get(), get())
        ))
    }
    single<IngestOutcomeWriter> {
        if (useDatabase) RepositoryOutcomeWriter(get(), get(), get()) { work -> dbQuery { work() } }
        else RepositoryOutcomeWriter(get(), get(), get())
    }
    single { IngestProcessor(get(), get(), get(), listeners = getAll<TransactionCreatedListener>()) }
    single { IngestEventService(get(), onAccepted = { get<IngestProcessor>().wake() }) }

    // SMS intake (AOS-203): device tokens + parser registry
    single { DeviceTokenService(get(), get()) }
    single { SmsParserRegistry() }
    single { SmsIntake(get(), get()) }
    single { dev.adminos.api.domain.ingestion.cash.CashIntake(get()) }

    // Statement import (AOS-202, D-37)
    single<StatementParser> { HttpStatementParser(get<AppConfig>().statementParser.url) }
    single { StatementImportService(get(), get()) }
    single { ConnectionService(get(), get(), get()) }
    single { GmailCronService(get(), get()) }

    // Financial Services
    single { CategorizationService(get()) }
    single { dev.adminos.api.domain.analytics.AnalyticsService(get()) }
    single {
        if (useDatabase) CategoryCorrectionService(get(), get()) { work -> dbQuery { work() } } else CategoryCorrectionService(get(), get())
    }
    single { AccountDiscoveryService(get(), get()) }
    single { SubscriptionDetectorService(get(), get()) }
    single { BillTrackingService(get(), get()) }

    // Agent Services
    single { AnomalyDetectorService(get(), get(), get()) }
    // Reactions to each new transaction (AOS-505); a new one is one `bind TransactionCreatedListener::class` line.
    single { AnomalyAlerts(get(), get()) } bind TransactionCreatedListener::class
    single { BillPaymentListener(get()) } bind TransactionCreatedListener::class

    // Notification Services
    // Delivery channels (AOS-504): a new channel is one `single { … } bind DeliveryChannel::class` line.
    single { InAppChannel() } bind DeliveryChannel::class
    // Email only when an SMTP server is configured (otherwise it is off, and the dispatcher logs that).
    SmtpConfig.from(env)?.let { smtp ->
        single<EmailSender> { SmtpEmailSender(smtp) }
        single { EmailChannel(get(), get<AppConfig>().app.appUrl) } bind DeliveryChannel::class
    }
    // Web Push only when VAPID keys are configured (AOS-510); Settings explains when it's off.
    val vapid = VapidConfig.from(env)
    if (vapid != null) {
        single<PushSender> { WebPushSender(vapid) }
        single { WebPushChannel(get(), get()) } bind DeliveryChannel::class
    }
    single { PushSubscriptionService(get(), getOrNull<WebPushChannel>(), vapid?.publicKey) }
    single { NotificationDispatcher(getAll<DeliveryChannel>(), get(), get()) }
    single { NotificationService(get(), get(), get()) }
    single { ReminderEngine(get(), get(), get()) }
    single { DropOffNudgeService(get(), get(), get()) }

    // Scheduled jobs (AOS-501, D-50): a new job is one `single { … } bind ScheduledJob::class` line.
    single { BillRemindersJob(get(), get<AppConfig>().scheduler.zone, get(), get()) } bind ScheduledJob::class
    single { OnboardingNudgesJob(get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { SubscriptionDetectionJob(get(), get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { PendingNotificationsJob(get(), get()) } bind ScheduledJob::class
    single { JobScheduler(getAll<ScheduledJob>(), get()) }
}
