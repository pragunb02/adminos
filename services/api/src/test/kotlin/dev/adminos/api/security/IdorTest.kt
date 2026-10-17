package dev.adminos.api.security

import dev.adminos.api.domain.agent.Anomaly
import dev.adminos.api.domain.agent.AnomalyRepository
import dev.adminos.api.domain.agent.AnomalyStatus
import dev.adminos.api.domain.agent.AnomalyType
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.coach.limits.LimitScope
import dev.adminos.api.domain.coach.limits.SpendingLimit
import dev.adminos.api.domain.coach.limits.SpendingLimitRepository
import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.LinkRelation
import dev.adminos.api.domain.financial.links.TransactionLink
import dev.adminos.api.domain.financial.links.TransactionLinkRepository
import dev.adminos.api.domain.financial.mandates.Mandate
import dev.adminos.api.domain.financial.mandates.MandateRail
import dev.adminos.api.domain.financial.mandates.MandateRepository
import dev.adminos.api.domain.financial.receipts.OrderReceipt
import dev.adminos.api.domain.financial.receipts.OrderReceiptRepository
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures
import dev.adminos.api.domain.identity.*
import dev.adminos.api.domain.ingestion.connection.ConnectionRepository
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.sync.SyncSession
import dev.adminos.api.domain.ingestion.sync.SyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationRepository
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.push.PushEndpoint
import dev.adminos.api.domain.notifications.push.PushKeys
import dev.adminos.api.domain.notifications.push.PushSubscription
import dev.adminos.api.domain.notifications.push.PushSubscriptionRepository
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import dev.adminos.api.security.SecurityTestKit.newUser
import dev.adminos.api.security.SecurityTestKit.realApp
import dev.adminos.api.security.SecurityTestKit.tokenFor
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.ktor.ext.get
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IDOR attack tests (AOS-619, D-91): user B, fully signed in, aims at user A's resources by ID.
 *
 * For every resource B gets **404 — the same answer as for an ID that doesn't exist**, so B can't even learn that
 * the ID is real; and A's resource is unchanged afterwards. Lists never show B anything of A's.
 */
class IdorTest {

    private val alice = newUser("alice")
    private val bob = newUser("bob")

    /** Everything Alice owns, seeded straight into the app's own repositories. */
    private class AliceData(
        val txn: Transaction, val otherTxn: Transaction, val subscription: Subscription, val bill: Bill, val anomaly: Anomaly,
        val link: TransactionLink, val connectionId: UUID, val syncId: UUID, val deviceTokenId: UUID, val deviceToken: String,
        val sessionId: UUID, val push: PushSubscription, val mandate: Mandate, val notification: Notification, val documentId: String,
        val receipt: OrderReceipt, val limit: SpendingLimit
    ) {
        fun ids() = listOf(txn.id, otherTxn.id, subscription.id, bill.id, anomaly.id, link.id, connectionId, syncId, deviceTokenId, sessionId,
            push.id, mandate.id, notification.id, receipt.id, limit.id).map { it.toString() } + documentId
    }

    private fun random(n: Int) = ByteArray(n).also(SecureRandom()::nextBytes)
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private suspend fun seed(app: Application, client: HttpClient): AliceData {
        val txns = app.get<TransactionRepository>()
        val txn = txns.save(Transaction(userId = alice.id, sourceType = "sms", type = TransactionType.DEBIT, amount = BigDecimal("4999.00"),
            merchantName = "Alice Secret Merchant", category = TransactionCategory.SHOPPING, transactedAt = Instant.now()))
        val other = txns.save(Transaction(userId = alice.id, sourceType = "gmail", type = TransactionType.DEBIT, amount = BigDecimal("4999.00"),
            merchantName = "Alice Secret Merchant", category = TransactionCategory.SHOPPING, transactedAt = Instant.now()))
        val sub = app.get<SubscriptionRepository>().save(Subscription(userId = alice.id, name = "Alice Stream", amount = BigDecimal("649"),
            billingCycle = BillingCycle.MONTHLY, isFlagged = true, flaggedReason = "unused"))
        val bill = app.get<BillRepository>().save(Bill(userId = alice.id, billerName = "Alice Power", amount = BigDecimal("1840"), dueDate = LocalDate.now().plusDays(3)))
        val anomaly = app.get<AnomalyRepository>().save(Anomaly(userId = alice.id, transactionId = txn.id, type = AnomalyType.LARGE_AMOUNT,
            confidenceScore = 0.9, reason = "large"))
        val link = app.get<TransactionLinkRepository>().save(TransactionLink(userId = alice.id, transactionId = txn.id, relatedTransactionId = other.id,
            source = "test", kind = LinkKind.SUGGESTED, relation = LinkRelation.DUPLICATE, score = 90, reason = "same amount", rulesVersion = "t"))
        val connection = app.get<ConnectionService>().createConnection(userId = alice.id, sourceType = SourceType.SMS)
        val sync = app.get<SyncSessionRepository>().save(SyncSession(userId = alice.id, connectionId = connection.id, syncType = SyncType.MANUAL))
        val device = app.get<DeviceTokenService>().issue(alice.id, "Alice Mac", setOf(Scopes.INGEST_SMS))
        val session = app.get<SessionRepository>().save(Session(userId = alice.id, tokenHash = JwtService.hashToken(b64(random(32))),
            expiresAt = Instant.now().plusSeconds(86_400)))
        val push = app.get<PushSubscriptionRepository>().upsert(PushSubscription(userId = alice.id,
            endpoint = PushEndpoint("https://fcm.googleapis.com/fcm/send/${b64(random(24))}"),
            keys = PushKeys(b64(byteArrayOf(0x04) + random(64)), b64(random(16)))))
        val mandate = app.get<MandateRepository>().save(Mandate(userId = alice.id, rail = MandateRail.UPI_AUTOPAY, payee = "Alice Gym",
            statusChangedAt = Instant.now()))
        val notification = app.get<NotificationRepository>().save(Notification(userId = alice.id, type = NotificationType.GENERAL,
            title = "Alice only", body = "for Alice", channel = NotificationChannel.IN_APP))
        // The vault is reached through its route, like the owner would (it encrypts and stores the file).
        val upload = client.submitFormWithBinaryData("/api/v1/documents", formData {
            append("category", "identity")
            append("title", "Alice passport")
            append("file", "%PDF-1.4\n% alice\n".toByteArray(), Headers.build {
                append(HttpHeaders.ContentType, "application/pdf"); append(HttpHeaders.ContentDisposition, "filename=\"alice.pdf\"")
            })
        }) { cookie(ACCESS_TOKEN_COOKIE, tokenFor(alice)); header(HttpHeaders.Origin, SecurityTestKit.config.app.appUrl) }
        assertEquals(HttpStatusCode.Created, upload.status, upload.bodyAsText())
        val documentId = Json.parseToJsonElement(upload.bodyAsText()).jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: error("no document id in ${upload.bodyAsText()}")
        // An order receipt of Alice's (AOS-903), dated now so this month's receipts and fees would show it.
        val receipt = app.get<OrderReceiptRepository>().save(ReceiptFixtures.receipt(alice.id, at = Instant.now()))
        val limit = app.get<SpendingLimitRepository>().save(SpendingLimit(userId = alice.id, scope = LimitScope.Merchant("Alice Secret Merchant"),
            label = "Alice Secret Merchant", period = LimitPeriod.MONTHLY, amount = BigDecimal("3000"), createdAt = Instant.now(), updatedAt = Instant.now()))
        return AliceData(txn, other, sub, bill, anomaly, link, connection.id, sync.id, device.record.id, device.token, session.id, push, mandate, notification, documentId,
            receipt, limit)
    }

    /** One attack: Bob calls [path] (with A's ID) and the same path with an ID that doesn't exist. */
    private data class Attack(val method: HttpMethod, val path: (String) -> String, val id: String, val body: String? = null)

    private suspend fun HttpClient.asBob(method: HttpMethod, path: String, body: String?) = request(path) {
        this.method = method
        cookie(ACCESS_TOKEN_COOKIE, tokenFor(bob))
        header(HttpHeaders.Origin, SecurityTestKit.config.app.appUrl) // a real browser on our pages (CSRF check)
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private fun errorCode(text: String) = Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test
    fun `Bob gets the same 404 for Alice's resources as for IDs that don't exist`() = realApp { app ->
        val a = seed(app, client)
        val attacks = listOf(
            Attack(HttpMethod.Patch, { "/api/v1/transactions/$it" }, a.txn.id.toString(), """{"category":"food"}"""),
            Attack(HttpMethod.Get, { "/api/v1/subscriptions/$it" }, a.subscription.id.toString()),
            Attack(HttpMethod.Patch, { "/api/v1/subscriptions/$it/dismiss" }, a.subscription.id.toString()),
            Attack(HttpMethod.Post, { "/api/v1/subscriptions/$it/cancel" }, a.subscription.id.toString()),
            Attack(HttpMethod.Get, { "/api/v1/bills/$it" }, a.bill.id.toString()),
            Attack(HttpMethod.Get, { "/api/v1/anomalies/$it" }, a.anomaly.id.toString()),
            Attack(HttpMethod.Patch, { "/api/v1/anomalies/$it" }, a.anomaly.id.toString(), """{"status":"dismissed"}"""),
            Attack(HttpMethod.Post, { "/api/v1/transactions/suggestions/$it/confirm" }, a.link.id.toString()),
            Attack(HttpMethod.Post, { "/api/v1/transactions/suggestions/$it/reject" }, a.link.id.toString()),
            Attack(HttpMethod.Get, { "/api/v1/documents/$it" }, a.documentId),
            Attack(HttpMethod.Get, { "/api/v1/documents/$it/file" }, a.documentId),
            Attack(HttpMethod.Patch, { "/api/v1/documents/$it" }, a.documentId, """{"title":"pwned"}"""),
            Attack(HttpMethod.Delete, { "/api/v1/documents/$it" }, a.documentId),
            Attack(HttpMethod.Delete, { "/api/v1/connections/$it" }, a.connectionId.toString()),
            Attack(HttpMethod.Post, { "/api/v1/connections/$it/sync" }, a.connectionId.toString()),
            Attack(HttpMethod.Get, { "/api/v1/sync/$it" }, a.syncId.toString()),
            Attack(HttpMethod.Delete, { "/api/v1/device-tokens/$it" }, a.deviceTokenId.toString()),
            Attack(HttpMethod.Delete, { "/api/v1/auth/sessions/$it" }, a.sessionId.toString()),
            Attack(HttpMethod.Post, { "/api/v1/receipts/$it/match" }, a.receipt.id.toString(), """{"transactionId":"${a.txn.id}"}"""),
            Attack(HttpMethod.Delete, { "/api/v1/receipts/$it/match" }, a.receipt.id.toString()),
            Attack(HttpMethod.Patch, { "/api/v1/coach/limits/$it" }, a.limit.id.toString(), """{"amount":"1"}"""),
            Attack(HttpMethod.Delete, { "/api/v1/coach/limits/$it" }, a.limit.id.toString()),
        )
        val leaks = attacks.mapNotNull { atk ->
            val real = client.asBob(atk.method, atk.path(atk.id), atk.body)
            val fake = client.asBob(atk.method, atk.path(UUID.randomUUID().toString()), atk.body)
            val realText = real.bodyAsText()
            when {
                real.status != HttpStatusCode.NotFound -> "${atk.method.value} ${atk.path("{alice}")} → ${real.status} $realText"
                errorCode(realText) != errorCode(fake.bodyAsText()) -> "${atk.method.value} ${atk.path("{alice}")} answers differently for a real ID"
                listOf("Alice", "alice").any { it in realText } -> "${atk.method.value} ${atk.path("{alice}")} leaks Alice's data"
                else -> null
            }
        }
        assertEquals(emptyList(), leaks)
    }

    @Test
    fun `Bob's attempts leave every one of Alice's resources unchanged`() = realApp { app ->
        val a = seed(app, client)
        client.asBob(HttpMethod.Patch, "/api/v1/transactions/${a.txn.id}", """{"category":"food"}""")
        client.asBob(HttpMethod.Patch, "/api/v1/subscriptions/${a.subscription.id}/dismiss", null)
        client.asBob(HttpMethod.Patch, "/api/v1/anomalies/${a.anomaly.id}", """{"status":"dismissed"}""")
        client.asBob(HttpMethod.Post, "/api/v1/transactions/suggestions/${a.link.id}/confirm", null)
        client.asBob(HttpMethod.Patch, "/api/v1/documents/${a.documentId}", """{"title":"pwned"}""")
        client.asBob(HttpMethod.Delete, "/api/v1/documents/${a.documentId}", null)
        client.asBob(HttpMethod.Delete, "/api/v1/connections/${a.connectionId}", null)
        client.asBob(HttpMethod.Delete, "/api/v1/device-tokens/${a.deviceTokenId}", null)
        client.asBob(HttpMethod.Delete, "/api/v1/auth/sessions/${a.sessionId}", null)
        client.asBob(HttpMethod.Delete, "/api/v1/push/subscriptions", """{"endpoint":"${a.push.endpoint.url}"}""")
        client.asBob(HttpMethod.Patch, "/api/v1/coach/limits/${a.limit.id}", """{"amount":"1"}""")
        client.asBob(HttpMethod.Delete, "/api/v1/coach/limits/${a.limit.id}", null)

        assertEquals(TransactionCategory.SHOPPING, app.get<TransactionRepository>().findById(a.txn.id)?.category)
        assertEquals(true, app.get<SubscriptionRepository>().findById(a.subscription.id)?.isFlagged)
        assertEquals(AnomalyStatus.OPEN, app.get<AnomalyRepository>().findById(a.anomaly.id)?.status)
        assertEquals(LinkKind.SUGGESTED, app.get<TransactionLinkRepository>().findById(a.link.id)?.kind)
        assertEquals(ConnectionStatus.CONNECTED, app.get<ConnectionRepository>().findById(a.connectionId)?.status)
        assertTrue(app.get<DeviceTokenService>().authenticate(a.deviceToken, Scopes.INGEST_SMS) != null, "Alice's device token still works")
        assertTrue(app.get<SessionRepository>().findActiveByUserId(alice.id).any { it.id == a.sessionId }, "Alice's session is still active")
        assertTrue(app.get<PushSubscriptionRepository>().findByUserId(alice.id).any { it.id == a.push.id }, "Alice still has push")
        assertEquals(BigDecimal("3000"), app.get<SpendingLimitRepository>().findById(alice.id, a.limit.id)?.amount, "Alice's limit is unchanged")
        val doc = client.get("/api/v1/documents/${a.documentId}") { cookie(ACCESS_TOKEN_COOKIE, tokenFor(alice)) }
        assertEquals(HttpStatusCode.OK, doc.status)
        assertTrue("Alice passport" in doc.bodyAsText(), "the title wasn't changed")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/documents/${a.documentId}/file") { cookie(ACCESS_TOKEN_COOKIE, tokenFor(alice)) }.status)
    }

    @Test
    fun `turning off push for Alice's device is not found for Bob`() = realApp { app ->
        val a = seed(app, client)
        val res = client.asBob(HttpMethod.Delete, "/api/v1/push/subscriptions", """{"endpoint":"${a.push.endpoint.url}"}""")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `none of Bob's lists show anything of Alice's`() = realApp { app ->
        val a = seed(app, client)
        val lists = listOf(
            "/api/v1/transactions", "/api/v1/transactions/summary", "/api/v1/transactions/suggestions", "/api/v1/subscriptions",
            "/api/v1/subscriptions/summary", "/api/v1/bills", "/api/v1/bills/upcoming", "/api/v1/anomalies", "/api/v1/documents",
            "/api/v1/connections", "/api/v1/device-tokens", "/api/v1/auth/sessions", "/api/v1/mandates", "/api/v1/notifications",
            "/api/v1/push", "/api/v1/dues", "/api/v1/accounts", "/api/v1/dashboard/home", "/api/v1/briefings",
            "/api/v1/analytics/summary", "/api/v1/analytics/merchants", "/api/v1/analytics/categories",
            "/api/v1/coach/leaks", "/api/v1/coach/leaks/transactions", "/api/v1/coach/lenses",
            "/api/v1/leaks/fees", "/api/v1/receipts",
            "/api/v1/coach/leaks", "/api/v1/coach/leaks/transactions", "/api/v1/coach/lenses", "/api/v1/coach/limits", "/api/v1/coach/settings",
        )
        val leaks = lists.mapNotNull { path ->
            val res = client.asBob(HttpMethod.Get, path, null)
            val text = res.bodyAsText()
            when {
                res.status != HttpStatusCode.OK -> "$path → ${res.status}"
                a.ids().any { it in text } || "Alice" in text -> "$path shows Alice's data"
                else -> null
            }
        }
        assertEquals(emptyList(), leaks)
        // And the same lists do show Alice her own data (the check above isn't vacuous).
        val mine = client.get("/api/v1/subscriptions") { cookie(ACCESS_TOKEN_COOKIE, tokenFor(alice)) }.bodyAsText()
        assertTrue(a.subscription.id.toString() in mine)
        assertFalse(a.subscription.id.toString() in client.asBob(HttpMethod.Get, "/api/v1/subscriptions", null).bodyAsText())
        // The coach's leaks (AOS-902): an online-shopping order of Alice's shows for her, and nothing of it for Bob.
        app.get<TransactionRepository>().save(Transaction(userId = alice.id, sourceType = "sms", type = TransactionType.DEBIT, amount = BigDecimal("1299.00"),
            merchantName = "Alice Leak Shop", category = TransactionCategory.SHOPPING, subcategory = "ecommerce", transactedAt = Instant.now()))
        for (path in listOf("/api/v1/coach/leaks", "/api/v1/coach/leaks/transactions")) {
            assertTrue("Alice Leak Shop" in client.get(path) { cookie(ACCESS_TOKEN_COOKIE, tokenFor(alice)) }.bodyAsText(), path)
            assertFalse("Alice" in client.asBob(HttpMethod.Get, path, null).bodyAsText(), path)
        }
        // Order receipts and their fees (AOS-903) list Alice's receipt for her (Bob's lists were checked above).
        for (path in listOf("/api/v1/receipts", "/api/v1/leaks/fees")) {
            assertTrue(a.receipt.id.toString() in client.get(path) { cookie(ACCESS_TOKEN_COOKIE, tokenFor(alice)) }.bodyAsText(), path)
        }
    }
}
