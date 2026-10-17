package dev.adminos.api.domain.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * A software passkey for tests (AOS-617): a P-256 key pair that answers WebAuthn options exactly as a platform
 * authenticator would ("none" attestation), so the real verification library checks real signatures. Every knob an
 * attacker could turn (origin, RP ID, user verification, counter, key, challenge) can be changed per call.
 */
class SoftAuthenticator(private val origin: String, private val rpId: String) {
    private val random = SecureRandom()
    val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val credentialId: ByteArray = ByteArray(32).also(random::nextBytes)
    var counter = 0L

    data class Tweaks(
        val origin: String? = null, val rpId: String? = null, val userVerified: Boolean = true, val challenge: String? = null,
        val type: String? = null, val counter: Long? = null, val signWith: KeyPair? = null
    )

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun challengeOf(optionsJson: String) =
        Json.parseToJsonElement(optionsJson).jsonObject["publicKey"]?.jsonObject?.get("challenge")?.jsonPrimitive?.content
            ?: error("no challenge in the options")

    private fun clientData(type: String, challenge: String, origin: String) =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun authData(rpId: String, flags: Int, counter: Long, attested: ByteArray = ByteArray(0)) =
        sha256(rpId.toByteArray()) + byteArrayOf(flags.toByte()) + ByteBuffer.allocate(4).putInt(counter.toInt()).array() + attested

    /** The answer to `navigator.credentials.create(options)`: the registration response JSON. */
    fun create(optionsJson: String, t: Tweaks = Tweaks()): String {
        val cd = clientData(t.type ?: "webauthn.create", t.challenge ?: challengeOf(optionsJson), t.origin ?: origin)
        val pub = keys.public as ECPublicKey
        val cose = Cbor.map(
            Cbor.int(1) to Cbor.int(2), Cbor.int(3) to Cbor.int(-7), Cbor.int(-1) to Cbor.int(1),
            Cbor.int(-2) to Cbor.bytes(fixed32(pub.w.affineX)), Cbor.int(-3) to Cbor.bytes(fixed32(pub.w.affineY))
        )
        val attested = ByteArray(16) + ByteBuffer.allocate(2).putShort(credentialId.size.toShort()).array() + credentialId + cose
        val flags = 0x01 or 0x40 or (if (t.userVerified) 0x04 else 0)
        val ad = authData(t.rpId ?: rpId, flags, t.counter ?: counter, attested)
        val attestation = Cbor.map(Cbor.text("fmt") to Cbor.text("none"), Cbor.text("attStmt") to Cbor.map(), Cbor.text("authData") to Cbor.bytes(ad))
        return """{"type":"public-key","id":"${b64(credentialId)}","rawId":"${b64(credentialId)}",""" +
            """"response":{"clientDataJSON":"${b64(cd)}","attestationObject":"${b64(attestation)}","transports":["internal"]},""" +
            """"clientExtensionResults":{}}"""
    }

    /** The answer to `navigator.credentials.get(options)`: the assertion response JSON. */
    fun get(optionsJson: String, userHandle: ByteArray, t: Tweaks = Tweaks()): String {
        counter = t.counter ?: (counter + 1)
        val cd = clientData(t.type ?: "webauthn.get", t.challenge ?: challengeOf(optionsJson), t.origin ?: origin)
        val ad = authData(t.rpId ?: rpId, 0x01 or (if (t.userVerified) 0x04 else 0), counter)
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign((t.signWith ?: keys).private); update(ad + sha256(cd)) }.sign()
        return """{"type":"public-key","id":"${b64(credentialId)}","rawId":"${b64(credentialId)}",""" +
            """"response":{"clientDataJSON":"${b64(cd)}","authenticatorData":"${b64(ad)}","signature":"${b64(sig)}","userHandle":"${b64(userHandle)}"},""" +
            """"clientExtensionResults":{}}"""
    }

    private fun fixed32(n: BigInteger): ByteArray {
        val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    /** Just enough CBOR (RFC 8949) for COSE keys and attestation objects. */
    object Cbor {
        private fun head(major: Int, len: Long): ByteArray = when {
            len < 24 -> byteArrayOf(((major shl 5) or len.toInt()).toByte())
            len < 256 -> byteArrayOf(((major shl 5) or 24).toByte(), len.toByte())
            len < 65536 -> byteArrayOf(((major shl 5) or 25).toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(((major shl 5) or 26).toByte()) + ByteBuffer.allocate(4).putInt(len.toInt()).array()
        }
        fun int(v: Long): ByteArray = if (v >= 0) head(0, v) else head(1, -1 - v)
        fun int(v: Int) = int(v.toLong())
        fun bytes(b: ByteArray) = head(2, b.size.toLong()) + b
        fun text(s: String) = s.toByteArray().let { head(3, it.size.toLong()) + it }
        fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
            write(head(5, entries.size.toLong()))
            entries.forEach { (k, v) -> write(k); write(v) }
        }.toByteArray()
    }
}
