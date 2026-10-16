package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditLog
import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.domain.common.DuplicateEntityException
import dev.adminos.api.domain.common.EntityNotFoundException
import dev.adminos.api.domain.common.RepositoryException
import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.LinkRelation
import dev.adminos.api.domain.financial.links.TransactionLink
import dev.adminos.api.domain.identity.OnboardingStatus
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.connection.UserConnection
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.notifications.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.BriefingsTable
import dev.adminos.api.infrastructure.database.tables.IngestionFingerprintsTable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every optional filter, null mapping and not-found path of the Exposed repositories, on real Postgres (AOS-312). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InfraRepositoryBranchesIntegrationTest {

    private val transactions = ExposedTransactionRepository()
    private val accounts = ExposedAccountRepository()
    private val subscriptions = ExposedSubscriptionRepository()
    private val bills = ExposedBillRepository()
    private val connections = ExposedConnectionRepository()
    private val anomalies = ExposedAnomalyRepository()
    private val notifications = ExposedNotificationRepository()
    private val users = ExposedUserRepository()
    private val briefings = ExposedBriefingRepository()
    private val insights = ExposedInsightRepository()
    private val audit = ExposedAuditRepository()
    private val links = ExposedTransactionLinkRepository()
    private val rules = ExposedCategoryRuleRepository()
    private val ingestEvents = ExposedIngestEventRepository()
    private val preferences = ExposedNotificationPreferencesRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private fun txn(user: UUID, amount: String = "100.00", type: TransactionType = TransactionType.DEBIT, category: TransactionCategory = TransactionCategory.FOOD,
                    merchant: String? = "Swiggy", at: Instant = now.minus(1, ChronoUnit.HOURS), account: UUID? = null, recurring: Boolean = false) =
        Transaction(userId = user, type = type, amount = BigDecimal(amount), category = category, merchantName = merchant, accountId = account,
            isRecurring = recurring, transactedAt = at)

    // ── Transactions ──

    @Test
    fun `a transaction without optional fields round-trips as nulls`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t = transactions.save(txn(user, merchant = null))
        val found = assertNotNull(transactions.findById(t.id))
        assertNull(found.paymentMethod); assertNull(found.metadata); assertNull(found.merchantName)
        transactions.update(found.copy(paymentMethod = PaymentMethod.UPI, metadata = mapOf("k" to "v")))
        assertEquals(PaymentMethod.UPI, transactions.findById(t.id)!!.paymentMethod)
        assertNull(transactions.findById(UUID.randomUUID()))
    }

    @Test
    fun `transactions are filtered by date range`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        transactions.save(txn(user, at = now.minus(3, ChronoUnit.DAYS)))
        transactions.save(txn(user, at = now.minus(1, ChronoUnit.DAYS)))
        val from = now.minus(2, ChronoUnit.DAYS)
        assertEquals(1, transactions.findByUserId(user, from = from).size)
        assertEquals(1, transactions.findByUserId(user, to = from).size)
        assertEquals(1L, transactions.countByUserId(user, from = from))
        assertEquals(1L, transactions.countByUserId(user, to = from))
    }

    @Test
    fun `transactions are filtered by category, type, account and recurrence`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val account = accounts.save(Account(userId = user, accountLast4 = "1234")).id
        transactions.save(txn(user, category = TransactionCategory.SHOPPING, account = account, recurring = true))
        transactions.save(txn(user, type = TransactionType.CREDIT, category = TransactionCategory.OTHER))
        assertEquals(1, transactions.findByUserId(user, category = TransactionCategory.SHOPPING).size)
        assertEquals(1, transactions.findByUserId(user, type = TransactionType.CREDIT).size)
        assertEquals(1, transactions.findByUserId(user, accountId = account).size)
        assertEquals(1, transactions.findByUserId(user, isRecurring = true).size)
        assertEquals(1L, transactions.countByUserId(user, category = TransactionCategory.SHOPPING))
        assertEquals(1L, transactions.countByUserId(user, type = TransactionType.CREDIT))
        assertEquals(1L, transactions.countByUserId(user, accountId = account))
        assertEquals(1L, transactions.countByUserId(user, isRecurring = false))
    }

    @Test
    fun `merchant search treats percent, underscore and backslash literally`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        transactions.save(txn(user, merchant = "100% Pure_Juice\\Bar"))
        transactions.save(txn(user, merchant = "1000 Pure Juice Bar"))
        assertEquals(1, transactions.findByUserId(user, merchantSearch = "0% pure_").size)
        assertEquals(1L, transactions.countByUserId(user, merchantSearch = "juice\\bar"))
        assertEquals(2L, transactions.countByUserId(user, merchantSearch = "pure"))
    }

    @Test
    fun `category average covers the category, all spend for an unknown category, and is zero without spend`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        assertEquals(BigDecimal.ZERO, transactions.getCategoryAverage(user, "food", 30))
        transactions.save(txn(user, amount = "100.00", category = TransactionCategory.FOOD))
        transactions.save(txn(user, amount = "301.00", category = TransactionCategory.FOOD))
        transactions.save(txn(user, amount = "50.00", category = TransactionCategory.SHOPPING))
        assertEquals(BigDecimal("200.50"), transactions.getCategoryAverage(user, "FOOD", 30))
        assertEquals(BigDecimal("150.33"), transactions.getCategoryAverage(user, "not-a-category", 30))
    }

    // ── Accounts ──

    @Test
    fun `an account updates and unknown lookups are null`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val a = accounts.save(Account(userId = user, accountLast4 = "5678", bankCode = "sbi"))
        accounts.update(a.copy(accountType = AccountType.CREDIT_CARD, bankName = "SBI Card", accountName = "SimplyCLICK", isPrimary = true))
        val found = assertNotNull(accounts.findById(a.id))
        assertEquals(AccountType.CREDIT_CARD, found.accountType); assertTrue(found.isPrimary); assertNull(found.metadata)
        assertNull(accounts.findById(UUID.randomUUID()))
        assertNull(accounts.findByUserAndBankAndLast4(user, "sbi", "0000"))
    }

    // ── Subscriptions ──

    private fun sub(user: UUID, merchant: String, status: SubscriptionStatus = SubscriptionStatus.ACTIVE, flagged: Boolean = false, createdAt: Instant = now) =
        Subscription(userId = user, name = merchant, merchantName = merchant, amount = BigDecimal("199.00"), billingCycle = BillingCycle.MONTHLY,
            status = status, isFlagged = flagged, createdAt = createdAt)

    @Test
    fun `subscriptions are filtered by status and flag and paged by cursor`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        subscriptions.save(sub(user, "Netflix", createdAt = now.minus(2, ChronoUnit.DAYS)))
        subscriptions.save(sub(user, "Hotstar", status = SubscriptionStatus.CANCELLED, createdAt = now.minus(1, ChronoUnit.DAYS)))
        subscriptions.save(sub(user, "Cult", flagged = true, createdAt = now))
        assertEquals(1, subscriptions.findByUserId(user, status = SubscriptionStatus.CANCELLED).size)
        assertEquals(1, subscriptions.findByUserId(user, flagged = true).size)
        assertEquals(listOf("Hotstar", "Netflix"), subscriptions.findByUserId(user, cursor = CursorCodec.encode(now.toString())).map { it.name })
        assertEquals(2L, subscriptions.countByUserId(user, flagged = false))
        assertEquals(3L, subscriptions.countByUserId(user))
    }

    @Test
    fun `a subscription without transactions or metadata round-trips and unknown lookups are null`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val s = subscriptions.save(sub(user, "Spotify"))
        val found = assertNotNull(subscriptions.findById(s.id))
        assertTrue(found.transactionIds.isEmpty()); assertNull(found.metadata)
        val t = transactions.save(txn(user, merchant = "Spotify")).id
        subscriptions.update(found.copy(transactionIds = listOf(t)))
        assertEquals(listOf(t), subscriptions.findById(s.id)!!.transactionIds)
        subscriptions.update(found.copy(transactionIds = emptyList()))
        assertTrue(subscriptions.findById(s.id)!!.transactionIds.isEmpty())
        assertNull(subscriptions.findById(UUID.randomUUID()))
        assertNull(subscriptions.findByUserAndMerchantAndCycle(user, "Spotify", BillingCycle.YEARLY))
    }

    // ── Bills ──

    private fun bill(user: UUID, due: LocalDate, status: BillStatus = BillStatus.UPCOMING) =
        Bill(userId = user, billerName = "BESCOM", billType = BillType.ELECTRICITY, amount = BigDecimal("1450.00"), dueDate = due, status = status)

    @Test
    fun `bills are filtered by status and due dates and paged by cursor`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val today = LocalDate.now()
        bills.save(bill(user, today.plusDays(1)))
        bills.save(bill(user, today.plusDays(5), BillStatus.PAID))
        bills.save(bill(user, today.plusDays(10)))
        assertEquals(1, bills.findByUserId(user, status = BillStatus.PAID).size)
        assertEquals(2, bills.findByUserId(user, from = today.plusDays(2)).size)
        assertEquals(2, bills.findByUserId(user, to = today.plusDays(5)).size)
        assertEquals(2, bills.findByUserId(user, cursor = CursorCodec.encode(today.plusDays(1).toString(), "asc")).size)
        assertEquals(2L, bills.countByUserId(user, BillStatus.UPCOMING))
        assertEquals(3L, bills.countByUserId(user))
    }

    @Test
    fun `upcoming bills across all users include due and upcoming ones only`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val soon = bills.save(bill(user, LocalDate.now().plusDays(2)))
        val paid = bills.save(bill(user, LocalDate.now().plusDays(2), BillStatus.PAID))
        val ids = bills.findUpcoming(withinDays = 3).map { it.id }
        assertTrue(soon.id in ids && paid.id !in ids)
        assertNull(bills.findById(UUID.randomUUID()))
        assertNull(bills.findById(soon.id)!!.metadata)
    }

    // ── Connections ──

    @Test
    fun `connections are listed per user and per source, and update without scopes`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val sms = connections.save(UserConnection(userId = user, sourceType = SourceType.SMS, status = ConnectionStatus.CONNECTED))
        connections.save(UserConnection(userId = user, sourceType = SourceType.PDF))
        assertEquals(2, connections.findByUserId(user).size)
        assertNull(connections.findById(sms.id)!!.oauthScope)
        assertTrue(connections.findConnectedBySourceType(SourceType.SMS).any { it.id == sms.id })
        connections.update(sms.copy(status = ConnectionStatus.ERROR, lastError = "token expired"))
        assertEquals(ConnectionStatus.ERROR, connections.findById(sms.id)!!.status)
        assertTrue(connections.findConnectedBySourceType(SourceType.SMS).none { it.id == sms.id })
    }

    @Test
    fun `unknown connections are null`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        assertNull(connections.findById(UUID.randomUUID()))
        assertNull(connections.findByUserAndSource(user, SourceType.GMAIL))
        assertNull(connections.findByGmailAddress("nobody-${UUID.randomUUID()}@gmail.com"))
        val c = connections.save(UserConnection(userId = user, sourceType = SourceType.GMAIL, oauthScope = listOf("email")))
        connections.update(c.copy(oauthScope = listOf("email", "gmail.readonly")))
        assertEquals(listOf("email", "gmail.readonly"), connections.findByUserAndSource(user, SourceType.GMAIL)!!.oauthScope)
    }

    // ── Anomalies ──

    @Test
    fun `anomalies are filtered by status, paged by cursor and resolved`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t = transactions.save(txn(user)).id
        val old = anomalies.save(Anomaly(userId = user, transactionId = t, type = AnomalyType.LARGE_AMOUNT, confidenceScore = 0.9, reason = "5x usual",
            createdAt = now.minus(1, ChronoUnit.DAYS)))
        anomalies.save(Anomaly(userId = user, transactionId = t, type = AnomalyType.ODD_HOURS, confidenceScore = 0.6, reason = "3am",
            status = AnomalyStatus.DISMISSED, resolvedBy = AnomalyResolver.USER, resolvedAt = now, createdAt = now))
        assertEquals(1, anomalies.findByUserId(user, status = AnomalyStatus.OPEN).size)
        assertEquals(listOf(old.id), anomalies.findByUserId(user, cursor = CursorCodec.encode(now.toString())).map { it.id })
        assertEquals(1L, anomalies.countByUserId(user, AnomalyStatus.DISMISSED))
        assertEquals(2L, anomalies.countByUserId(user))
        anomalies.update(old.copy(status = AnomalyStatus.CONFIRMED_SAFE, resolvedBy = AnomalyResolver.AGENT, resolvedAt = now, agentExplanation = "salary week"))
        assertEquals(AnomalyResolver.AGENT, anomalies.findById(old.id)!!.resolvedBy)
        anomalies.update(old.copy(resolvedBy = null))
        assertNull(anomalies.findById(old.id)!!.resolvedBy)
        assertNull(anomalies.findById(UUID.randomUUID()))
    }

    // ── Notifications ──

    @Test
    fun `notifications without data round-trip, update and are listed by type and as pending`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val n = notifications.save(Notification(userId = user, type = NotificationType.BILL_DUE, title = "BESCOM due", body = "₹1,450 due tomorrow", channel = NotificationChannel.PUSH))
        assertNull(notifications.findById(n.id)!!.data)
        assertTrue(notifications.findPending().any { it.id == n.id })
        assertEquals(1, notifications.findByUserAndType(user, NotificationType.BILL_DUE).size)
        notifications.update(n.copy(status = NotificationStatus.SENT, sentAt = now, fcmMessageId = "fcm-1"))
        assertEquals(NotificationStatus.SENT, notifications.findById(n.id)!!.status)
        assertTrue(notifications.findPending().none { it.id == n.id })
        assertNull(notifications.findById(UUID.randomUUID()))
    }

    // ── Users ──

    private fun newUserModel(email: String = "u-${UUID.randomUUID()}@example.in", googleId: String = "g-${UUID.randomUUID()}") =
        User(email = email, googleId = googleId, name = "Ravi", role = UserRole.OWNER, onboardingStatus = OnboardingStatus.STARTED)

    @Test
    fun `unknown users are null`() = runBlocking<Unit> {
        assertNull(users.findByGoogleId("g-${UUID.randomUUID()}"))
        assertNull(users.findByEmail("nobody-${UUID.randomUUID()}@example.in"))
    }

    @Test
    fun `a second user with the same Google id is a duplicate`() = runBlocking<Unit> {
        val first = users.save(newUserModel())
        assertFailsWith<DuplicateEntityException> { users.save(newUserModel(googleId = first.googleId)) }
    }

    @Test
    fun `any other database error is a repository error`() = runBlocking<Unit> {
        // A NUL byte passes Exposed's checks but Postgres rejects it.
        val e = assertFailsWith<RepositoryException> { users.save(newUserModel().copy(name = "Ravi\u0000Kumar")) }
        assertTrue(e !is DuplicateEntityException)
    }

    @Test
    fun `updating a user that does not exist fails`() = runBlocking<Unit> {
        assertFailsWith<EntityNotFoundException> { users.update(newUserModel()) }
    }

    @Test
    fun `users who have not finished onboarding are found`() = runBlocking<Unit> {
        val started = users.save(newUserModel())
        val done = users.save(newUserModel().copy(onboardingStatus = OnboardingStatus.COMPLETED))
        val ids = users.findByOnboardingIncomplete().map { it.id }
        assertTrue(started.id in ids && done.id !in ids)
    }

    // ── Briefings and insights ──

    private fun briefing(user: UUID, createdAt: Instant = now, weeksAgo: Long = 0) = Briefing(userId = user,
        periodStart = LocalDate.now().minusWeeks(weeksAgo + 1), periodEnd = LocalDate.now().minusWeeks(weeksAgo),
        content = "You spent ₹4,200 this week", modelUsed = "m", promptVersion = "v1", tokensUsed = 10, createdAt = createdAt)

    @Test
    fun `briefings are paged by cursor and unknown ones are null`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        assertNull(briefings.findLatestByUserId(user))
        val old = briefings.save(briefing(user, now.minus(7, ChronoUnit.DAYS), weeksAgo = 1))
        briefings.save(briefing(user, now))
        assertEquals(listOf(old.id), briefings.findByUserId(user, cursor = CursorCodec.encode(now.toString())).map { it.id })
        assertEquals(2, briefings.findByUserId(user).size)
        assertNull(briefings.findById(UUID.randomUUID()))
        val found = briefings.findById(old.id)!!
        assertNull(found.contentStructured); assertNull(found.topCategories)
    }

    @Test
    fun `stored top categories with missing keys read as blank and zero`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val b = briefings.save(briefing(user).copy(contentStructured = mapOf("highlights" to listOf("food"))))
        dbQuery { BriefingsTable.update({ BriefingsTable.id eq b.id }) { it[topCategories] = "[{}]" } }
        val found = briefings.findById(b.id)!!
        assertEquals(listOf(CategoryAmount("", BigDecimal("0"))), found.topCategories)
        assertEquals(mapOf("highlights" to listOf("food")), found.contentStructured)
    }

    @Test
    fun `insights without a payload round-trip and are listed with or without a status`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        insights.save(Insight(userId = user, type = InsightType.SPENDING_SPIKE, title = "Food is up 40%", body = "b"))
        insights.save(Insight(userId = user, type = InsightType.BILL_DUE, title = "BESCOM", body = "b", status = InsightStatus.SEEN))
        assertEquals(2, insights.findByUserId(user).size)
        assertNull(insights.findByUserId(user, InsightStatus.PENDING).single().actionPayload)
    }

    // ── Audit ──

    @Test
    fun `audit entries with metadata and without states round-trip`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        audit.save(AuditLog(userId = user, actor = ActorType.SYSTEM, action = "sync.start", entityType = "sync_session",
            metadata = buildJsonObject { put("source", JsonPrimitive("gmail")) }))
        val log = audit.findByUserId(user).single()
        assertNull(log.beforeState); assertNull(log.afterState)
        assertEquals(JsonPrimitive("gmail"), log.metadata!!["source"])
    }

    // ── Small not-found and optional paths ──

    @Test
    fun `unknown ids are null in every by-id lookup`() = runBlocking<Unit> {
        val id = UUID.randomUUID()
        assertNull(ExposedDeviceRepository().findById(id))
        assertNull(ExposedSessionRepository().findByTokenHash("hash-$id"))
        assertNull(ExposedDeviceTokenRepository().findById(id))
        assertNull(ExposedSyncSessionRepository().findById(id))
        assertNull(links.findById(id))
        assertNull(preferences.findByUserId(id))
    }

    @Test
    fun `links are filtered by relation`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t = transactions.save(txn(user)).id
        for (r in listOf(LinkRelation.DUPLICATE, LinkRelation.REFUND)) {
            links.save(TransactionLink(userId = user, transactionId = t, source = "gmail", kind = LinkKind.SUGGESTED, relation = r, score = 6, reason = "x", rulesVersion = "v"))
        }
        assertEquals(1, links.findByUser(user, LinkKind.SUGGESTED, LinkRelation.REFUND).size)
        assertEquals(2, links.findByUser(user, LinkKind.SUGGESTED).size)
    }

    @Test
    fun `a category rule lookup needs a payee key`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        assertNull(rules.findFor(user, null, null))
        rules.upsert(CategoryRule(userId = user, match = RuleMatch.MERCHANT, value = "ramu chai", category = TransactionCategory.FOOD))
        assertEquals(TransactionCategory.FOOD, rules.findFor(user, vpa = null, merchant = "ramu chai")!!.category)
        assertNull(rules.findFor(user, vpa = "ramu@ybl", merchant = null))
    }

    @Test
    fun `intake events keep payment method and category when present`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        fun e(ref: String, pm: PaymentMethod?, cat: TransactionCategory?) = IngestEvent(userId = user, source = IngestSource.MANUAL, sourceRef = ref,
            occurredAt = now, direction = IngestDirection.DEBIT, amount = BigDecimal("40.00"), paymentMethod = pm, category = cat)
        ingestEvents.saveNew(listOf(e("cash:a", PaymentMethod.CASH, TransactionCategory.FOOD), e("cash:b", null, null)))
        val byRef = ingestEvents.findByUser(user).associateBy { it.sourceRef }
        assertEquals(PaymentMethod.CASH, byRef["cash:a"]!!.paymentMethod); assertEquals(TransactionCategory.FOOD, byRef["cash:a"]!!.category)
        assertNull(byRef["cash:b"]!!.paymentMethod); assertNull(byRef["cash:b"]!!.category)
    }

    @Test
    fun `no bill reminder days are stored as an empty list`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        preferences.save(NotificationPreferences(userId = user, billReminderDays = emptyList()))
        assertEquals(emptyList(), preferences.findByUserId(user)!!.billReminderDays)
    }

    @Test
    fun `the ingestion fingerprints table matches its migration`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val entity = UUID.randomUUID()
        dbQuery {
            IngestionFingerprintsTable.insert {
                it[userId] = user; it[fingerprint] = "f".repeat(64); it[sourceType] = "sms"; it[entityId] = entity; it[createdAt] = now
            }
        }
        val row = dbQuery { IngestionFingerprintsTable.selectAll().where { IngestionFingerprintsTable.userId eq user }.single() }
        assertEquals("transaction", row[IngestionFingerprintsTable.entityType])
        assertEquals(entity, row[IngestionFingerprintsTable.entityId])
    }
}
