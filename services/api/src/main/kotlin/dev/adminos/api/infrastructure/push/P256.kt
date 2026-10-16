package dev.adminos.api.infrastructure.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * NIST P-256 keys in the raw forms Web Push uses (AOS-510): a public key is the 65-byte uncompressed
 * point `0x04 ‖ x ‖ y`, a private key the 32-byte scalar. Only JDK crypto is used.
 */
internal object P256 {
    val params: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)

    private val keys = KeyFactory.getInstance("EC")

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** @throws IllegalArgumentException unless [raw] is an uncompressed point on the curve */
    fun publicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "A P-256 public key is 65 bytes starting with 0x04" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        require(isOnCurve(point)) { "The public key is not a point on P-256" }
        return keys.generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    fun privateKey(raw: ByteArray): ECPrivateKey {
        require(raw.size == 32) { "A P-256 private key is 32 bytes" }
        val d = BigInteger(1, raw)
        require(d.signum() > 0 && d < params.order) { "The private key is out of range" }
        return keys.generatePrivate(ECPrivateKeySpec(d, params)) as ECPrivateKey
    }

    fun encode(key: ECPublicKey): ByteArray = byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)

    fun encode(key: ECPrivateKey): ByteArray = fixed(key.s)

    /** The public key belonging to [private] (d·G), to check a configured key pair matches. */
    fun publicOf(private: ECPrivateKey): ECPublicKey {
        val point = multiply(params.generator, private.s)
        return keys.generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    /** Big-endian, left-padded to 32 bytes. */
    private fun fixed(n: BigInteger): ByteArray {
        val bytes = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - bytes.size) + bytes
    }

    private val p: BigInteger get() = (params.curve.field as java.security.spec.ECFieldFp).p

    private fun isOnCurve(pt: ECPoint): Boolean {
        val (x, y) = pt.affineX to pt.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val lhs = y.modPow(BigInteger.TWO, p)
        val rhs = x.modPow(BigInteger.valueOf(3), p).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return lhs == rhs
    }

    // Affine double-and-add; only used once at start-up to verify configuration, so speed doesn't matter.
    private fun multiply(point: ECPoint, k: BigInteger): ECPoint {
        var result: ECPoint = ECPoint.POINT_INFINITY
        var addend = point
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    private fun add(a: ECPoint, b: ECPoint): ECPoint {
        if (a == ECPoint.POINT_INFINITY) return b
        if (b == ECPoint.POINT_INFINITY) return a
        val lambda = if (a == b) {
            if (a.affineY.signum() == 0) return ECPoint.POINT_INFINITY
            a.affineX.pow(2).multiply(BigInteger.valueOf(3)).add(params.curve.a)
                .multiply(a.affineY.shiftLeft(1).modInverse(p)).mod(p)
        } else {
            if (a.affineX == b.affineX) return ECPoint.POINT_INFINITY
            b.affineY.subtract(a.affineY).multiply(b.affineX.subtract(a.affineX).modInverse(p)).mod(p)
        }
        val x = lambda.pow(2).subtract(a.affineX).subtract(b.affineX).mod(p)
        val y = lambda.multiply(a.affineX.subtract(x)).subtract(a.affineY).mod(p)
        return ECPoint(x, y)
    }
}

/** URL-safe base64 without padding, as Web Push uses everywhere. Decoding accepts padding too. */
internal object B64 {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** @throws IllegalArgumentException if [text] isn't URL-safe base64 */
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))
}
