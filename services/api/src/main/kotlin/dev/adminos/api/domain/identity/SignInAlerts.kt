package dev.adminos.api.domain.identity

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A coarse, fixed-vocabulary name for a browser, e.g. "Chrome on Mac" (AOS-620). Never the raw User-Agent:
 * whoever signs in chooses that string, so it must not reach alerts or decisions as text.
 */
object DeviceLabel {
    fun of(userAgent: String?): String {
        val ua = userAgent.orEmpty()
        val os = when {
            "iPhone" in ua -> "iPhone"
            "iPad" in ua -> "iPad"
            "Android" in ua -> "Android"
            "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
            "Windows" in ua -> "Windows"
            "CrOS" in ua -> "ChromeOS"
            "Linux" in ua -> "Linux"
            else -> null
        }
        val browser = when {
            "Edg/" in ua -> "Edge"
            "Firefox/" in ua || "FxiOS/" in ua -> "Firefox"
            "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
            "Safari/" in ua -> "Safari"
            else -> null
        }
        return when {
            os != null && browser != null -> "$browser on $os"
            os != null -> os
            browser != null -> browser
            else -> "Unknown device"
        }
    }
}

/** What the owner is told about (AOS-620). Each kind has fixed wording; only our own facts are filled in. */
sealed interface SecurityAlert {
    val title: String
    val body: String

    data class NewSignIn(val device: String, val at: String) : SecurityAlert {
        override val title = "New sign-in to AdminOS"
        override val body = "Signed in on $device at $at. If this wasn't you, open Settings → Security and sign out everywhere."
    }

    data class RefusedSignIn(val attempts: Int, val since: String, val maskedAccount: String?) : SecurityAlert {
        override val title = "Sign-in to AdminOS refused"
        override val body = buildString {
            append(if (attempts == 1) "A sign-in was refused" else "$attempts sign-ins were refused since $since")
            if (maskedAccount != null) append(" (a Google account $maskedAccount)")
            append(". Nothing was opened. If it was you, use the owner's Google account.")
        }
    }

    data class SessionKeyReused(val device: String) : SecurityAlert {
        override val title = "A copied sign-in was blocked"
        override val body = "An old session key from $device was used again, so that session was signed out. " +
            "If you didn't just sign out there, sign out everywhere in Settings → Security."
    }
}

/** Delivers a [SecurityAlert] to the owner over every channel they have (push, email, in-app). Port: notifications implements it. */
fun interface SecurityNotifier {
    suspend fun notify(userId: UUID, alert: SecurityAlert)
}

/**
 * Sign-in alerts (AOS-620, D-91 layer 3): on every new sign-in from a device or address not seen before, on refused
 * sign-ins (at most one alert per [refusedQuietPeriod], counting the ones in between, so a stranger can't flood the
 * owner's phone), and when a rotated session key is reused. Delivery failures are logged, never thrown: an alert
 * must not break sign-in, and the event stays in the audit log.
 */
class SignInAlerts(
    private val notifier: SecurityNotifier?,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    private val refusedQuietPeriod: Duration = Duration.ofMinutes(15)
) {
    private val log = LoggerFactory.getLogger(SignInAlerts::class.java)
    private val time = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

    private data class Refusals(val count: Int, val since: Instant, val lastAlert: Instant?)
    private val refusals = AtomicReference<Refusals?>(null)

    private fun at(i: Instant) = time.format(i.atZone(zone)) + " IST".takeIf { zone.id == "Asia/Kolkata" }.orEmpty()

    suspend fun newSignIn(ownerId: UUID, device: String) = send(ownerId, SecurityAlert.NewSignIn(device, at(clock.instant())))

    suspend fun sessionKeyReused(ownerId: UUID, device: String) = send(ownerId, SecurityAlert.SessionKeyReused(device))

    /** A refused sign-in. [ownerId] is null before the owner's first sign-in (nobody to tell yet). */
    suspend fun refused(ownerId: UUID?, email: String?) {
        val now = clock.instant()
        val r = refusals.updateAndGet { old ->
            val live = old?.takeIf { Duration.between(it.since, now) <= refusedQuietPeriod.multipliedBy(4) }
            Refusals((live?.count ?: 0) + 1, live?.since ?: now, live?.lastAlert)
        } ?: return
        if (ownerId == null) return
        if (r.lastAlert != null && Duration.between(r.lastAlert, now) < refusedQuietPeriod) return
        refusals.set(Refusals(0, now, now))
        send(ownerId, SecurityAlert.RefusedSignIn(r.count, at(r.since), maskEmail(email)))
    }

    private suspend fun send(ownerId: UUID, alert: SecurityAlert) {
        val n = notifier ?: return log.warn("Security alert '{}' not sent: no notifier configured", alert.title)
        try {
            n.notify(ownerId, alert)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Security alert '{}' for {} could not be delivered", alert.title, ownerId, e)
        }
    }

    companion object {
        /**
         * `s•••@e•••.com`: enough for the owner to recognise an account, too little to carry a message. Only letters
         * and digits survive, so an attacker can't put a phone number or a link into the owner's notification.
         */
        fun maskEmail(email: String?): String? {
            val e = email?.trim()?.lowercase() ?: return null
            val at = e.lastIndexOf('@').takeIf { it > 0 && it < e.length - 1 } ?: return null
            val local = e.substring(0, at).filter { it in 'a'..'z' || it in '0'..'9' }
            val domain = e.substring(at + 1)
            val host = domain.substringBefore('.').filter { it in 'a'..'z' || it in '0'..'9' }
            val tld = domain.substringAfterLast('.', "").filter { it in 'a'..'z' }.take(10)
            if (local.isEmpty() || host.isEmpty() || tld.isEmpty()) return null
            return "${local.first()}•••@${host.first()}•••.$tld"
        }
    }
}
