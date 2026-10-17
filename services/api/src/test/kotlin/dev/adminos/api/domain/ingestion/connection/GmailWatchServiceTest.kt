package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** AOS-405: Gmail push registration, renewal and stop, against in-memory connections and fake Google. */
class GmailWatchServiceTest {

    private val now = Instant.parse("2026-10-17T06:00:00Z")
    private val clock = TestClock(now)
    private val connections = InMemoryConnectionRepository()
    private val encryptor = randomTokenEncryptor()
    private val api = FakeGmailPushApi(expiresAt = now.plus(Duration.ofDays(7)))
    private val refresher = FakeTokenRefresher { clock.instant().plusSeconds(3599) }
    private val tokens = GmailAccessTokens(connections, refresher, encryptor, clock)
    private val topic = "projects/adminos-dev/topics/gmail-push"
    private val service = GmailWatchService(connections, api, tokens, GmailWatchSettings(topic, clock = clock))

    private class Stored(val connection: UserConnection, val accessToken: String, val refreshToken: String)

    private suspend fun gmail(
        watch: GmailWatch = GmailWatch.NONE,
        status: ConnectionStatus = ConnectionStatus.CONNECTED,
        tokenExpiresAt: Instant = now.plusSeconds(3000),
        source: SourceType = SourceType.GMAIL
    ): Stored {
        val id = UUID.randomUUID()
        val access = runtimeToken()
        val refresh = runtimeToken("1//")
        val conn = connections.save(UserConnection(
            id = id, userId = UUID.randomUUID(), sourceType = source, status = status,
            accessToken = encryptor.encrypt(access, "user_connections:$id:access_token"),
            refreshToken = encryptor.encrypt(refresh, "user_connections:$id:refresh_token"),
            tokenExpiresAt = tokenExpiresAt, gmailAddress = "u-$id@example.com", historyId = "1200", watch = watch
        ))
        return Stored(conn, access, refresh)
    }

    private suspend fun stored(c: Stored) = assertNotNull(connections.findById(c.connection.id))

    // --- register ---

    @Test
    fun `registering a watch stores gmail's expiry and history id and clears an old error`() = runBlocking<Unit> {
        val c = gmail(watch = GmailWatch(error = "Gmail push: Gmail is unreachable", checkedAt = now.minusSeconds(60)))

        val outcome = service.register(c.connection)

        val watch = stored(c).watch
        assertEquals(WatchOutcome.Registered(watch), outcome)
        assertEquals(now.plus(Duration.ofDays(7)), watch.expiresAt)
        assertEquals("9876543", watch.historyId)
        assertNull(watch.error)
        assertEquals(now, watch.checkedAt)
    }

    @Test
    fun `the watch is set on the configured topic with the decrypted access token`() = runBlocking<Unit> {
        val c = gmail()

        service.register(c.connection)

        assertEquals(listOf(c.accessToken), api.watchTokens.toList())
        assertEquals(listOf(topic), api.watchTopics.toList())
        assertTrue(refresher.refreshTokens.isEmpty(), "a token valid for 50 more minutes is not refreshed")
    }

    @Test
    fun `registering never touches the history id that syncs start from`() = runBlocking<Unit> {
        val c = gmail()

        service.register(c.connection)

        assertEquals("1200", stored(c).historyId)
    }

    @Test
    fun `an expiring access token is refreshed before the watch call`() = runBlocking<Unit> {
        val c = gmail(tokenExpiresAt = now.plusSeconds(30))

        service.register(c.connection)

        assertEquals(listOf(c.refreshToken), refresher.refreshTokens.toList())
        assertEquals(refresher.issued.toList(), api.watchTokens.toList())
    }

    @Test
    fun `a watch refused with 401 is retried once with a refreshed token`() = runBlocking<Unit> {
        val c = gmail()
        api.watchFailures += GmailPushException.Unauthorized()

        val outcome = service.register(c.connection)

        assertIs<WatchOutcome.Registered>(outcome)
        assertEquals(listOf(c.accessToken, refresher.issued.single()), api.watchTokens.toList())
    }

    @Test
    fun `a failed watch is recorded and the connection stays connected`() = runBlocking<Unit> {
        val c = gmail()
        api.watchFailures += GmailPushException.Rejected(403)

        val outcome = service.register(c.connection)

        assertEquals(WatchOutcome.Failed("Gmail push: Gmail answered HTTP 403"), outcome)
        val after = stored(c)
        assertEquals(ConnectionStatus.CONNECTED, after.status)
        assertEquals("Gmail push: Gmail answered HTTP 403", after.watch.error)
        assertEquals(now, after.watch.checkedAt)
        assertNull(after.watch.expiresAt)
    }

    @Test
    fun `a failed renewal keeps the watch gmail is still honouring`() = runBlocking<Unit> {
        val expiring = now.plus(Duration.ofHours(5))
        val c = gmail(watch = GmailWatch(expiresAt = expiring, historyId = "100"))
        api.watchFailures += GmailPushException.Unavailable(IOException("connection reset"))

        service.register(c.connection)

        val watch = stored(c).watch
        assertEquals(expiring, watch.expiresAt)
        assertEquals("100", watch.historyId)
        assertEquals("Gmail push: Gmail is unreachable", watch.error)
    }

    @Test
    fun `a second 401 after refreshing is recorded as a failure`() = runBlocking<Unit> {
        val c = gmail()
        api.watchFailures += GmailPushException.Unauthorized()
        api.watchFailures += GmailPushException.Unauthorized()

        val outcome = service.register(c.connection)

        assertEquals(WatchOutcome.Failed("Gmail push: Gmail did not accept the access token"), outcome)
        assertEquals(ConnectionStatus.CONNECTED, stored(c).status)
    }

    @Test
    fun `a revoked grant puts the connection in error and is recorded on the watch`() = runBlocking<Unit> {
        val c = gmail(tokenExpiresAt = now.minusSeconds(1))
        refresher.failure = GmailAccessException.Revoked()

        val outcome = service.register(c.connection)

        assertEquals(WatchOutcome.Failed(GmailAccessException.REVOKED_REASON), outcome)
        val after = stored(c)
        assertEquals(ConnectionStatus.ERROR, after.status)
        assertEquals(GmailAccessException.REVOKED_REASON, after.lastError)
        assertTrue(api.watchTokens.isEmpty())
    }

    @Test
    fun `an unreachable token service is recorded and does not change the connection`() = runBlocking<Unit> {
        val c = gmail(tokenExpiresAt = now.minusSeconds(1))
        refresher.failure = GmailAccessException.Unavailable()

        val outcome = service.register(c.connection)

        assertIs<WatchOutcome.Failed>(outcome)
        assertEquals(ConnectionStatus.CONNECTED, stored(c).status)
        assertEquals("Google's token service is unreachable", stored(c).watch.error)
    }

    @Test
    fun `without a topic nothing is registered`() = runBlocking<Unit> {
        val off = GmailWatchService(connections, api, tokens, GmailWatchSettings("", clock = clock))
        val c = gmail()

        assertEquals(WatchOutcome.Skipped, off.register(c.connection))
        assertTrue(api.watchTokens.isEmpty())
        assertEquals(GmailWatch.NONE, stored(c).watch)
    }

    @Test
    fun `a disconnected or non-gmail connection is never registered`() = runBlocking<Unit> {
        val disconnected = gmail(status = ConnectionStatus.DISCONNECTED)
        val sms = gmail(source = SourceType.SMS)

        assertEquals(WatchOutcome.Skipped, service.register(disconnected.connection))
        assertEquals(WatchOutcome.Skipped, service.register(sms.connection))
        assertTrue(api.watchTokens.isEmpty())
    }

    // --- renewal ---

    @Test
    fun `renewal renews missing, expired and soon-ending watches and leaves the rest`() = runBlocking<Unit> {
        val missing = gmail()
        val expired = gmail(watch = GmailWatch(expiresAt = now.minusSeconds(60)))
        val endingToday = gmail(watch = GmailWatch(expiresAt = now.plus(Duration.ofHours(23))))
        val fine = gmail(watch = GmailWatch(expiresAt = now.plus(Duration.ofDays(3)), historyId = "5"))

        val result = service.renewDue()

        assertEquals(WatchRenewalResult(connections = 4, renewed = 3, notDue = 1, failed = 0), result)
        assertEquals(now.plus(Duration.ofDays(7)), stored(missing).watch.expiresAt)
        assertEquals(now.plus(Duration.ofDays(7)), stored(expired).watch.expiresAt)
        assertEquals(now.plus(Duration.ofDays(7)), stored(endingToday).watch.expiresAt)
        assertEquals(now.plus(Duration.ofDays(3)), stored(fine).watch.expiresAt)
    }

    @Test
    fun `renewal stops for disconnected and revoked connections`() = runBlocking<Unit> {
        gmail(status = ConnectionStatus.DISCONNECTED, watch = GmailWatch(expiresAt = now.plusSeconds(60)))
        gmail(status = ConnectionStatus.ERROR)

        val result = service.renewDue()

        assertEquals(WatchRenewalResult(0, 0, 0, 0), result)
        assertTrue(api.watchTokens.isEmpty())
    }

    @Test
    fun `a failed renewal is counted and retried by the next run`() = runBlocking<Unit> {
        val c = gmail()
        api.watchFailures += GmailPushException.Rejected(500)

        val first = service.renewDue()
        clock.advance(Duration.ofHours(6))
        val second = service.renewDue()

        assertEquals(1, first.failed)
        assertEquals(1, second.renewed)
        assertNull(stored(c).watch.error)
        assertEquals(2, api.watchTokens.size)
    }

    @Test
    fun `an unexpected error on one connection does not stop the others`() = runBlocking<Unit> {
        val broken = gmail()
        connections.update(broken.connection.copy(refreshToken = null, tokenExpiresAt = now.minusSeconds(1)))
        val good = gmail()
        api.watchFailures.clear()
        val throwing = object : GmailPushApi by api {
            override suspend fun watch(accessToken: String, topic: String): WatchRegistration {
                if (accessToken == good.accessToken) return api.watch(accessToken, topic)
                throw IllegalStateException("bug")
            }
        }
        val svc = GmailWatchService(connections, throwing, tokens, GmailWatchSettings(topic, clock = clock))
        val third = gmail()

        val result = svc.renewDue()

        assertEquals(3, result.connections)
        assertEquals(1, result.renewed, "only the good one is renewed")
        assertEquals(2, result.failed, "the unreadable one is recorded, the throwing one is isolated")
        assertEquals("The stored Gmail tokens can't be used", stored(broken).watch.error)
        assertNull(stored(third).watch.expiresAt)
    }

    @Test
    fun `renewal without a topic reports push as off and calls nothing`() = runBlocking<Unit> {
        gmail()
        val off = GmailWatchService(connections, api, tokens, GmailWatchSettings(" ", clock = clock))

        assertEquals(WatchRenewalResult(0, 0, 0, 0, disabled = true), off.renewDue())
        assertTrue(api.watchTokens.isEmpty())
    }

    // --- stop ---

    @Test
    fun `stopping an active watch calls users stop with the access token`() = runBlocking<Unit> {
        val c = gmail(watch = GmailWatch(expiresAt = now.plus(Duration.ofDays(2))))

        service.stop(c.connection)

        assertEquals(listOf(c.accessToken), api.stopTokens.toList())
    }

    @Test
    fun `stopping ignores gmail and token failures`() = runBlocking<Unit> {
        val c = gmail(watch = GmailWatch(expiresAt = now.plus(Duration.ofDays(2))))
        api.stopFailure = GmailPushException.Rejected(500)

        service.stop(c.connection)

        val expiredToken = gmail(watch = GmailWatch(expiresAt = now.plus(Duration.ofDays(2))), tokenExpiresAt = now.minusSeconds(1))
        refresher.failure = GmailAccessException.Unavailable()
        service.stop(expiredToken.connection)

        assertEquals(1, api.stopTokens.size)
    }

    @Test
    fun `there is nothing to stop without an active watch, a topic, or for other sources`() = runBlocking<Unit> {
        val lapsed = gmail(watch = GmailWatch(expiresAt = now.minusSeconds(1)))
        val none = gmail()
        val sms = gmail(source = SourceType.SMS, watch = GmailWatch(expiresAt = now.plus(Duration.ofDays(1))))
        val active = gmail(watch = GmailWatch(expiresAt = now.plus(Duration.ofDays(1))))
        val off = GmailWatchService(connections, api, tokens, GmailWatchSettings("", clock = clock))

        service.stop(lapsed.connection)
        service.stop(none.connection)
        service.stop(sms.connection)
        off.stop(active.connection)

        assertTrue(api.stopTokens.isEmpty())
    }

    // --- the job and the settings ---

    @Test
    fun `the renewal job runs every 6 hours and reports counts`() = runBlocking<Unit> {
        gmail()
        gmail(watch = GmailWatch(expiresAt = now.plus(Duration.ofDays(5))))
        val job = GmailWatchRenewalJob(service)

        val result = job.run()

        assertEquals(JobName("gmail-watch-renewal"), job.name)
        assertEquals(Schedule.Every(Duration.ofHours(6)), job.schedule)
        assertEquals(mapOf("connections" to 2, "renewed" to 1, "notDue" to 1, "failed" to 0), result.counts)
    }

    @Test
    fun `the renewal job says when push is off`() = runBlocking<Unit> {
        val job = GmailWatchRenewalJob(GmailWatchService(connections, api, tokens, GmailWatchSettings("", clock = clock)))

        assertEquals(mapOf("disabled" to 1), job.run().counts)
    }

    @Test
    fun `a malformed topic or renewal window is refused at start-up`() {
        assertFailsWith<IllegalArgumentException> { GmailWatchSettings("gmail-push") }
        assertFailsWith<IllegalArgumentException> { GmailWatchSettings("projects/p/topics/") }
        assertFailsWith<IllegalArgumentException> { GmailWatchSettings(topic, renewWithin = Duration.ofDays(7)) }
        assertFailsWith<IllegalArgumentException> { GmailWatchSettings(topic, renewWithin = Duration.ofHours(-1)) }
        assertTrue(GmailWatchSettings(topic).enabled)
        assertFalse(GmailWatchSettings("").enabled)
    }

    @Test
    fun `a watch needs renewal when missing or ending within the window`() {
        val window = Duration.ofHours(24)
        assertTrue(GmailWatch.NONE.needsRenewal(now, window))
        assertTrue(GmailWatch(expiresAt = now.plus(window)).needsRenewal(now, window))
        assertFalse(GmailWatch(expiresAt = now.plus(window).plusSeconds(1)).needsRenewal(now, window))
        assertFalse(GmailWatch.NONE.isActive(now))
        assertFalse(GmailWatch(expiresAt = now).isActive(now))
        assertTrue(GmailWatch(expiresAt = now.plusSeconds(1)).isActive(now))
    }

    @Test
    fun `gmail's answers and refreshed tokens validate themselves and never print tokens`() {
        assertFailsWith<IllegalArgumentException> { WatchRegistration("history_1700000000", now) }
        assertFailsWith<IllegalArgumentException> { RefreshedAccessToken(" ", now) }
        val token = runtimeToken()
        assertFalse(token in RefreshedAccessToken(token, now).toString())
    }
}
