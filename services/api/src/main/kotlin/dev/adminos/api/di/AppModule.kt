package dev.adminos.api.di

import dev.adminos.api.config.AppConfig
import dev.adminos.api.infrastructure.database.repositories.ExposedIngestEventRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedForecastHistoryRepository
import dev.adminos.api.infrastructure.database.DatabaseFactory
import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.audit.AuditRepository
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.identity.*
import dev.adminos.api.domain.ingestion.connection.ConnectionRepository
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.GmailAccessTokens
import dev.adminos.api.domain.ingestion.connection.GmailPushApi
import dev.adminos.api.domain.ingestion.connection.GmailWatchRenewalJob
import dev.adminos.api.domain.ingestion.connection.GmailWatchService
import dev.adminos.api.domain.ingestion.connection.GmailWatchSettings
import dev.adminos.api.infrastructure.google.HttpGmailPushApi
import dev.adminos.api.domain.ingestion.connection.InMemoryConnectionRepository
import dev.adminos.api.domain.ingestion.connection.InMemoryOAuthStateRepository
import dev.adminos.api.domain.ingestion.connection.OAuthStateRepository
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
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

private const val LEARNING_FLAG = dev.adminos.api.config.LearnedCategorizationConfig.FLAG

/**
 * Builds the DI module. The defaults read the real environment; tests pass [useDatabase] and [env]
 * to build each configuration (testability seam, AOS-312).
 */
internal fun buildAppModule(
    useDatabase: Boolean = DatabaseFactory.isConfigured(),
    env: (String) -> String? = System::getenv
): Module = module {
    // Spending coach (Epic E9): its own module, so its tasks don't edit this file.
    includes(coachKoinModule(useDatabase))
    single { AppConfig.load() }
    single { TokenEncryptor(get<AppConfig>().security.tokenEncryptionKey) }
    single { JwtService(get<AppConfig>().auth) }
    single { GoogleOAuthClient(get<AppConfig>().auth) }

    // Repository bindings: Exposed when a database is configured, else in-memory (dev mode)
    if (useDatabase) {
        // Identity
        single<UserRepository> { ExposedUserRepository() }
        single<SessionRepository> { ExposedSessionRepository() }
        single<PasskeyRepository> { ExposedPasskeyRepository() }
        single<RecoveryCodeRepository> { ExposedRecoveryCodeRepository() }
        single<DeviceRepository> { ExposedDeviceRepository() }
        // Ingestion
        single<ConnectionRepository> { ExposedConnectionRepository() }
        single<OAuthStateRepository> { ExposedOAuthStateRepository() }
        single<SyncSessionRepository> { ExposedSyncSessionRepository() }
        single<IngestEventRepository> { ExposedIngestEventRepository() }
        single<DeviceTokenRepository> { ExposedDeviceTokenRepository() }
        single<TransactionLinkRepository> { ExposedTransactionLinkRepository() }
        single<CategoryRuleRepository> { ExposedCategoryRuleRepository() }
        // Read through confirmed merchant merges (AOS-709).
        single<dev.adminos.api.domain.analytics.AnalyticsRepository> { dev.adminos.api.domain.analytics.AliasedAnalyticsRepository(ExposedAnalyticsRepository(), get()) }
        single<dev.adminos.api.domain.forecast.ForecastHistoryRepository> { ExposedForecastHistoryRepository() }
        // Financial
        single<TransactionRepository> { ExposedTransactionRepository() }
        single<AccountRepository> { ExposedAccountRepository() }
        single<SubscriptionRepository> { ExposedSubscriptionRepository() }
        single<PriceRiseRepository> { ExposedPriceRiseRepository() }
        single<dev.adminos.api.domain.financial.grouping.MerchantAliasRepository> { ExposedMerchantAliasRepository() }
        single<BillRepository> { ExposedBillRepository() }
        // Agent
        single<AnomalyRepository> { ExposedAnomalyRepository() }
        single<AnomalyToleranceRepository> { ExposedAnomalyToleranceRepository() }
        single<BriefingRepository> { ExposedBriefingRepository() }
        single<InsightRepository> { ExposedInsightRepository() }
        // Notifications
        single<NotificationPreferencesRepository> { ExposedNotificationPreferencesRepository() }
        single<NotificationRepository> { ExposedNotificationRepository() }
        // Audit
        single<AuditRepository> { ExposedAuditRepository() }
        // Scheduling
        single<JobRunRepository> { ExposedJobRunRepository() }
        single<dev.adminos.api.domain.documents.DocumentRepository> { ExposedDocumentRepository() }
        single<dev.adminos.api.domain.financial.mandates.MandateRepository> { ExposedMandateRepository() }
        single<dev.adminos.api.domain.notifications.push.PushSubscriptionRepository> { ExposedPushSubscriptionRepository() }
        single<dev.adminos.api.domain.financial.receipts.OrderReceiptRepository> { ExposedOrderReceiptRepository() }
        single<dev.adminos.api.domain.financial.trips.TravelBookingRepository> { ExposedTravelBookingRepository() }
        single<dev.adminos.api.domain.financial.trips.TripEditRepository> { ExposedTripEditRepository() }
        single<dev.adminos.api.domain.financial.splits.SplitRepository> { ExposedSplitRepository(get()) }
    } else {
        // InMemory fallback for local dev / testing
        single<UserRepository> { InMemoryUserRepository() }
        single<SessionRepository> { InMemorySessionRepository() }
        single<PasskeyRepository> { InMemoryPasskeyRepository() }
        single<RecoveryCodeRepository> { InMemoryRecoveryCodeRepository() }
        single<DeviceRepository> { InMemoryDeviceRepository() }
        single<ConnectionRepository> { InMemoryConnectionRepository() }
        single<OAuthStateRepository> { InMemoryOAuthStateRepository() }
        single<SyncSessionRepository> { InMemorySyncSessionRepository() }
        single<IngestEventRepository> { InMemoryIngestEventRepository() }
        single<DeviceTokenRepository> { InMemoryDeviceTokenRepository() }
        single<TransactionLinkRepository> { InMemoryTransactionLinkRepository() }
        single<CategoryRuleRepository> { InMemoryCategoryRuleRepository() }
        single<dev.adminos.api.domain.analytics.AnalyticsRepository> { dev.adminos.api.domain.analytics.AliasedAnalyticsRepository(dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository(get()), get()) }
        single<dev.adminos.api.domain.forecast.ForecastHistoryRepository> { dev.adminos.api.domain.forecast.InMemoryForecastHistoryRepository(get(), get()) }
        single<TransactionRepository> { InMemoryTransactionRepository() }
        single<AccountRepository> { InMemoryAccountRepository() }
        single<SubscriptionRepository> { InMemorySubscriptionRepository() }
        single<PriceRiseRepository> { InMemoryPriceRiseRepository() }
        single<dev.adminos.api.domain.financial.grouping.MerchantAliasRepository> { dev.adminos.api.domain.financial.grouping.InMemoryMerchantAliasRepository() }
        single<BillRepository> { InMemoryBillRepository() }
        single<AnomalyRepository> { InMemoryAnomalyRepository() }
        single<AnomalyToleranceRepository> { InMemoryAnomalyToleranceRepository() }
        single<BriefingRepository> { InMemoryBriefingRepository() }
        single<InsightRepository> { InMemoryInsightRepository() }
        single<NotificationPreferencesRepository> { InMemoryNotificationPreferencesRepository() }
        single<NotificationRepository> { InMemoryNotificationRepository() }
        single<AuditRepository> { InMemoryAuditRepository() }
        single<JobRunRepository> { InMemoryJobRunRepository() }
        single<dev.adminos.api.domain.documents.DocumentRepository> { dev.adminos.api.domain.documents.InMemoryDocumentRepository() }
        single<dev.adminos.api.domain.financial.mandates.MandateRepository> { dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository() }
        single<dev.adminos.api.domain.notifications.push.PushSubscriptionRepository> { dev.adminos.api.domain.notifications.push.InMemoryPushSubscriptionRepository() }
        single<dev.adminos.api.domain.financial.receipts.OrderReceiptRepository> { dev.adminos.api.domain.financial.receipts.InMemoryOrderReceiptRepository() }
        single<dev.adminos.api.domain.financial.trips.TravelBookingRepository> { dev.adminos.api.domain.financial.trips.InMemoryTravelBookingRepository() }
        single<dev.adminos.api.domain.financial.trips.TripEditRepository> { dev.adminos.api.domain.financial.trips.InMemoryTripEditRepository() }
        single<dev.adminos.api.domain.financial.splits.SplitRepository> { dev.adminos.api.domain.financial.splits.InMemorySplitRepository() }
    }

    // Services — depend on interfaces, unchanged
    single { AuditService(get(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())) }
    // Sign-in alerts go to the owner over every channel they have (AOS-620); notifications implement the port.
    single {
        val notifications = lazy { get<NotificationService>() }
        SignInAlerts(SecurityNotifier { userId, alert ->
            notifications.value.send(userId, NotificationType.SECURITY, alert.title, alert.body, mapOf("url" to "/settings/security"))
        })
    }
    single { AuthService(get(), get(), get(), get(), get<AppConfig>().auth, alerts = get()) }
    // Passkeys, the second factor (AOS-617): RP ID and origin come from APP_URL.
    single<WebAuthnCeremonies> { dev.adminos.api.infrastructure.webauthn.YubicoWebAuthn(get<AppConfig>().app.appUrl) }
    single { PasskeyService(get(), get(), get(), get(), get(), alerts = get()) }

    // Redis queue publisher — only if REDIS_URL is configured. Parsed now, so a missing Redis password
    // (REDIS_PASSWORD, or in the URL) stops the start-up instead of the first job (AOS-634).
    val redisUrl = env("REDIS_URL") ?: ""
    if (redisUrl.isNotBlank()) {
        val redisUri = dev.adminos.api.infrastructure.queue.RedisUrl.parse(redisUrl, env("REDIS_PASSWORD"))
        single { RedisClient.create(redisUri) }
        // TODO: Call AsynqPublisher.close() from Ktor ApplicationStopped event
        single { AsynqPublisher(get()) }
    }
    // Documents vault (AOS-507, D-60): Cloudflare R2 / S3 when configured; memory only in dev mode; otherwise refused clearly.
    val documentStorage = dev.adminos.api.config.DocumentStorageConfig.from(env)
    single<dev.adminos.api.domain.documents.DocumentStorage> {
        when {
            documentStorage != null -> dev.adminos.api.infrastructure.documents.S3DocumentStorage(documentStorage)
            !useDatabase -> dev.adminos.api.infrastructure.documents.InMemoryDocumentStorage().also {
                org.slf4j.LoggerFactory.getLogger("Documents").warn("DOCS_S3_* not set: documents are kept IN MEMORY and lost on restart (dev mode only).")
            }
            else -> dev.adminos.api.infrastructure.documents.UnconfiguredDocumentStorage().also {
                org.slf4j.LoggerFactory.getLogger("Documents").warn("DOCS_S3_* not set: the documents vault is off (uploads are refused).")
            }
        }
    }
    single<dev.adminos.api.domain.documents.DocumentSealer> { dev.adminos.api.infrastructure.documents.AesGcmDocumentSealer(get()) }
    single { dev.adminos.api.domain.documents.DocumentService(get(), get(), get()) }

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
    // Learned categoriser (AOS-707, D-67): off unless LEARNED_CATEGORIZATION_ENABLED=true. It runs inside the API and
    // sends nothing anywhere. When on: the last pipeline step (after every rule) and a nightly retrain.
    val learning = dev.adminos.api.config.LearnedCategorizationConfig.enabled(env)
    org.slf4j.LoggerFactory.getLogger("LearnedCategorization")
        .info("Learning categories from corrections is {}", if (learning) "on (local model, nothing is sent)" else "off ($LEARNING_FLAG is not true)")
    single<dev.adminos.api.domain.financial.learning.CategoryModelRepository> {
        if (useDatabase) ExposedCategoryModelRepository() else dev.adminos.api.domain.financial.learning.InMemoryCategoryModelRepository()
    }
    single { dev.adminos.api.domain.financial.learning.CategoryModelStore(get()) }
    single { dev.adminos.api.domain.financial.learning.LearnedCategorizer(get(), get(), get()) }
    single {
        val prior = dev.adminos.api.domain.financial.learning.CategoryPrior.examples(
            dev.adminos.api.domain.financial.evaluation.GoldenSet.load(), get<MerchantNormalizer>()::normalize)
        dev.adminos.api.domain.financial.learning.CategoryModelTrainingService(get(), get(), get(), prior, learning)
    }
    // Review queue with active learning (AOS-708): the learned model's and the AI's unsure guesses, and the local golden set.
    single<dev.adminos.api.domain.financial.review.GoldenCandidateRepository> {
        if (useDatabase) ExposedGoldenCandidateRepository() else dev.adminos.api.domain.financial.review.InMemoryGoldenCandidateRepository()
    }
    single { dev.adminos.api.domain.financial.review.CategoryReviewService(get(), get(), get(), get()) }
    single {
        val learnedStep = if (learning) listOf(LearnedCategoryStep(get())) else emptyList()
        IngestPipeline(listOf(
            ToTransactionStep(), NormalizeMerchantStep(get()), CrossSourceDedupStep(get(), get()), CategorizeStep(get()),
            TransferStep(get(), get()), RefundReversalStep(get(), get(), get())
        ) + learnedStep)
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
    single { SmsIntake(get(), get(), get(), get(), get()) }
    single { dev.adminos.api.domain.ingestion.whatsapp.WhatsAppImportService(get(), get(), get(), get()) }
    single { dev.adminos.api.domain.ingestion.cash.CashIntake(get()) }
    // Cash from free text (AOS-718, D-77): the deterministic grammar. TODO(AOS-718 → AOS-702): once the AI layer's
    // AiProvider exists, an AI CashPhraseParser plugs in as FallbackCashPhraseParser(rules, ai), behind its consent toggle and eval gate.
    single<dev.adminos.api.domain.ingestion.cash.CashPhraseParser> {
        dev.adminos.api.domain.ingestion.cash.RuleCashPhraseParser(dev.adminos.api.domain.ingestion.cash.CashCategoryGuesser(get()))
    }

    // Statement import (AOS-202, D-37)
    single<StatementParser> { HttpStatementParser(get<AppConfig>().statementParser.url) }
    single { StatementImportService(get(), get()) }
    // Gmail push (AOS-405, D-63): the API calls users.watch/stop itself; off without GOOGLE_GMAIL_PUBSUB_TOPIC.
    single<GmailPushApi> { HttpGmailPushApi() }
    single { GmailAccessTokens(get(), get<GoogleOAuthClient>(), get()) }
    single { GmailWatchService(get(), get(), get(), GmailWatchSettings(get<AppConfig>().google.gmailPubsubTopic)) }
    single { ConnectionService(get(), get(), get(), get(), get()) }
    // OAuth state (CSRF) + PKCE for Connect Gmail (AOS-406, D-64)
    single { OAuthStateService(get(), get()) }
    single { GmailCronService(get(), get(), get()) }

    // Financial Services
    single { CategorizationService(get()) }
    single { dev.adminos.api.domain.analytics.AnalyticsService(get()) }
    single {
        if (useDatabase) CategoryCorrectionService(get(), get()) { work -> dbQuery { work() } } else CategoryCorrectionService(get(), get())
    }
    single { AccountDiscoveryService(get(), get()) }
    single { SubscriptionDetectorService(get(), get(), get(), get(), get()) }
    // Look-alike merchants proposed as one, confirmed in Review (AOS-709, D-75)
    single { dev.adminos.api.domain.financial.grouping.MerchantGroupingService(get(), get(), get(), get()) }
    single { dev.adminos.api.domain.agent.SubscriptionReviewService(get(), get(), get()) }
    single { BillTrackingService(get(), get()) }
    single { dev.adminos.api.domain.financial.mandates.MandateService(get(), get()) }
    single { DuesService(get(), get(), get()) }
    // Pay-later (AOS-909, D-89): provider bills into dues; repayments are transfers that pay them.
    single { dev.adminos.api.domain.financial.paylater.PayLaterBills(get(), get()) }
    single { dev.adminos.api.domain.financial.paylater.PayLaterService(get(), get()) }
    // Trips (AOS-910, D-90): computed on read from transactions, refunds, bookings and the user's edits.
    single { dev.adminos.api.domain.financial.trips.TravelBookings(get()) }
    single { dev.adminos.api.domain.financial.trips.TripService(get(), get(), get(), get()) }
    // Order receipts and hidden fees (AOS-903, D-88): matched to payments in both directions.
    single { dev.adminos.api.domain.financial.matching.PaymentMatcher() }
    single { dev.adminos.api.domain.financial.receipts.ReceiptMatchingService(get(), get(), get()) }
    single { dev.adminos.api.domain.financial.receipts.HiddenFeesService(get()) }
    // Month-end forecast and money left until salary (AOS-714): deterministic, from the same history and dues.
    single { dev.adminos.api.domain.forecast.ForecastService(get(), get(), get()) }

    // Agent Services
    // Robust per-merchant baselines + tolerance learnt from answers (AOS-713, D-73)
    single { AnomalyDetectorService(get(), get(), get(), get()) }
    single {
        if (useDatabase) AnomalyReviewService(get(), get(), get(), get(), java.time.Clock.systemUTC()) { work -> dev.adminos.api.infrastructure.database.dbQueryReadCommitted { work() } }
        else AnomalyReviewService(get(), get(), get(), get())
    }
    // Reactions to each new transaction (AOS-505); a new one is one `bind TransactionCreatedListener::class` line.
    single { AnomalyAlerts(get(), get()) } bind TransactionCreatedListener::class
    single { BillPaymentListener(get()) } bind TransactionCreatedListener::class
    single { dev.adminos.api.domain.financial.mandates.MandateDebitListener(get()) } bind TransactionCreatedListener::class
    single { dev.adminos.api.domain.financial.receipts.ReceiptPaymentListener(get()) } bind TransactionCreatedListener::class
    // Splitwise (AOS-911, D-111): imports, Review, and new bank records offered to entries still waiting for one.
    single {
        if (useDatabase) dev.adminos.api.domain.financial.splits.SplitService(get(), get(), get(), get()) { work -> dbQuery { work() } }
        else dev.adminos.api.domain.financial.splits.SplitService(get(), get(), get(), get())
    } bind TransactionCreatedListener::class

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
    single { dev.adminos.api.domain.identity.UserCalendar(get()) }
    single { ReminderEngine(get(), get(), get(), get()) }
    single { RenewalReminders(get(), get(), get(), get()) }
    // Subscription price-rise alerts, once per new price (AOS-715, D-74); sent by the detection job.
    single { PriceRiseAlerts(get(), get(), get(), get()) }
    single { DropOffNudgeService(get(), get(), get()) }

    // Scheduled jobs (AOS-501, D-50): a new job is one `single { … } bind ScheduledJob::class` line.
    single { BillRemindersJob(get(), get(), get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { OnboardingNudgesJob(get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { SubscriptionDetectionJob(get(), get(), get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { WasteScoreService(get(), get(), get(), java.time.Clock.systemUTC(), get<AppConfig>().scheduler.zone) }
    single { SubscriptionWasteScoreJob(get(), get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { PendingNotificationsJob(get(), get()) } bind ScheduledJob::class
    // Weekly briefing from real data (AOS-506)
    single { dev.adminos.api.domain.agent.briefing.BriefingSources(get(), get(), get(), get(), get()) }
    single { dev.adminos.api.domain.agent.briefing.WeeklyBriefingService(get(), get(), get()) }
    single { dev.adminos.api.domain.agent.briefing.WeeklyBriefingJob(get(), get(), get()) } bind ScheduledJob::class
    single { dev.adminos.api.domain.documents.DocumentExpiryJob(get(), get(), get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { dev.adminos.api.domain.ingestion.webhook.GmailFallbackSyncJob(get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    single { GmailWatchRenewalJob(get()) } bind ScheduledJob::class
    if (learning) {
        single { dev.adminos.api.domain.financial.learning.CategoryModelTrainingJob(get(), get(), get<AppConfig>().scheduler.zone) } bind ScheduledJob::class
    }
    single { dev.adminos.api.domain.financial.receipts.ReceiptMatchingJob(get()) } bind ScheduledJob::class
    // The AI layer (AOS-702, D-67) and the AI fallback categorizer (AOS-512, D-62): every feature off by default.
    aiDefinitions(useDatabase, env)
    // "Ask your money" (AOS-710): read-only tools over the analytics, through the AI gateway only.
    single { dev.adminos.api.domain.ask.AskToolRunner(get()) }
    single { dev.adminos.api.domain.ask.RecentAsks(java.time.Clock.systemUTC()) }
    single {
        val consents = get<dev.adminos.api.domain.ai.AiConsentCheck>()
        dev.adminos.api.domain.ask.AskService(get(), get(), dev.adminos.api.domain.ask.AskQuota(java.time.Clock.systemUTC()), java.time.Clock.systemUTC(),
            consent = { user -> consents.allows(user, dev.adminos.api.domain.ai.AiFeature.ASK) }, recent = get())
    }
    // Thumbs up/down for the eval set (AOS-711): masked question + plan, encrypted at rest.
    single<dev.adminos.api.domain.ask.AskFeedbackRepository> {
        if (useDatabase) dev.adminos.api.infrastructure.database.repositories.ExposedAskFeedbackRepository(get()) else dev.adminos.api.domain.ask.InMemoryAskFeedbackRepository()
    }
    single { dev.adminos.api.domain.ask.AskFeedbackService(get(), get(), java.time.Clock.systemUTC()) }
    single { JobScheduler(getAll<ScheduledJob>(), get()) }
}
