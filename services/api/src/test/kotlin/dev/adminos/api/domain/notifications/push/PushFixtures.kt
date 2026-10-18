package dev.adminos.api.domain.notifications.push

import dev.adminos.api.infrastructure.push.B64
import dev.adminos.api.infrastructure.push.P256
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.UUID

/** A browser's push keys, made per test run (never literal keys in the repo); tests can decrypt with [uaPrivateKey]. */
internal object PushFixtures {
    private val pair = P256.generate()
    val UA_PUBLIC: String = B64.encode(P256.encode(pair.public as ECPublicKey))
    val uaPrivateKey: ECPrivateKey = pair.private as ECPrivateKey
    val AUTH: String = B64.encode(ByteArray(16).also(SecureRandom()::nextBytes))
    val keys = PushKeys(UA_PUBLIC, AUTH)

    fun endpoint(device: String = UUID.randomUUID().toString()) = PushEndpoint("https://web.push.apple.com/QG$device")

    fun subscription(userId: UUID, device: String = UUID.randomUUID().toString(), label: String? = "iPhone · Safari") =
        PushSubscription(userId = userId, endpoint = endpoint(device), keys = keys, label = label)
}

/** Saves a subscription the test expects to be stored (AOS-627: saving can refuse). */
internal suspend fun PushSubscriptionRepository.saved(subscription: PushSubscription): PushSubscription =
    (save(subscription) as? PushSaveResult.Saved)?.subscription ?: error("expected the subscription to be saved")

/** A push sender that answers from a script per device and records what it sent. */
internal class ScriptedPushSender(private val answer: (PushSubscription) -> PushSendResult = { PushSendResult.Accepted }) : PushSender {
    val sent = mutableListOf<Pair<PushSubscription, PushMessage>>()
    override suspend fun send(subscription: PushSubscription, message: PushMessage): PushSendResult {
        sent += subscription to message
        return answer(subscription)
    }
}
