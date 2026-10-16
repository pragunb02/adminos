package dev.adminos.api.infrastructure.push

import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Prints a new VAPID key pair for `.env` (AOS-510): `./gradlew -q vapidKeys`.
 * Run once; changing the keys later means every device has to turn push on again.
 */
fun main() {
    val pair = P256.generate()
    println("VAPID_PUBLIC_KEY=${B64.encode(P256.encode(pair.public as ECPublicKey))}")
    println("VAPID_PRIVATE_KEY=${B64.encode(P256.encode(pair.private as ECPrivateKey))}")
    println("VAPID_SUBJECT=mailto:you@example.com")
}
