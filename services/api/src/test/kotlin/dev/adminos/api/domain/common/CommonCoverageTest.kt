package dev.adminos.api.domain.common

import dev.adminos.api.domain.common.workflow.GmailSignals
import dev.adminos.api.domain.common.workflow.SubscriptionData
import dev.adminos.api.domain.common.workflow.TransactionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CursorCodec error paths, the API envelope, repository exceptions and workflow data types (AOS-312). */
class CommonCoverageTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    // ── CursorCodec ──

    @Test
    fun `cursor encodes desc by default`() {
        assertEquals("2026-10-10T06:30:00Z" to "desc", CursorCodec.decode(CursorCodec.encode("2026-10-10T06:30:00Z")))
    }

    @Test
    fun `cursor without a direction decodes as desc`() {
        assertEquals("k" to "desc", CursorCodec.decode(b64("""{"key":"k"}""")))
    }

    @Test
    fun `cursor key returns only the sort key`() {
        assertEquals("k", CursorCodec.key(CursorCodec.encode("k", "asc")))
    }

    @Test
    fun `cursor that is not base64 is rejected`() {
        assertFailsWith<IllegalArgumentException> { CursorCodec.decode("%%%") }
    }

    @Test
    fun `cursor that is not json is rejected`() {
        assertFailsWith<IllegalArgumentException> { CursorCodec.decode(b64("hello")) }
    }

    @Test
    fun `cursor that is a json array is rejected`() {
        assertFailsWith<IllegalArgumentException> { CursorCodec.decode(b64("[1,2]")) }
    }

    @Test
    fun `cursor without a key is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { CursorCodec.decode(b64("""{"dir":"asc"}""")) }
        assertEquals("Invalid pagination cursor", e.message)
    }

    // ── ApiResponse / ApiError / Pagination ──

    @Test
    fun `success envelope carries data and meta without error or pagination`() {
        val r = ApiResponse.success("₹649", "req-1")

        assertTrue(r.success); assertEquals("₹649", r.data); assertNull(r.error); assertNull(r.pagination)
        assertEquals("req-1", r.meta.requestId)
        Instant.parse(r.meta.timestamp)
    }

    @Test
    fun `paginated envelope carries the pagination`() {
        val p = Pagination(cursor = "c", hasMore = true, total = 42)

        val r = ApiResponse.paginated(listOf(1, 2), p, "req-2")

        assertTrue(r.success); assertEquals(listOf(1, 2), r.data); assertEquals(p, r.pagination)
    }

    @Test
    fun `error envelope carries the error and no data`() {
        val r = ApiResponse.error(ApiError.RATE_LIMITED, "req-3")

        assertFalse(r.success); assertNull(r.data); assertEquals("RATE_001", r.error!!.code)
    }

    @Test
    fun `pagination defaults to an empty last page`() {
        val p = Pagination()
        assertNull(p.cursor); assertFalse(p.hasMore); assertEquals(0L, p.total)
    }

    @Test
    fun `envelope serializes with optional fields and round-trips`() {
        val details = buildJsonObject { put("field", "amount") }
        val r = ApiResponse<String>(
            success = false, data = "x", error = ApiError("VALIDATION_001", "bad", details),
            pagination = Pagination("c", true, 3), meta = Meta("req-4", "2026-10-16T00:00:00Z")
        )
        val serializer = ApiResponse.serializer(kotlinx.serialization.serializer<String>())

        val json = Json.encodeToString(serializer, r)

        assertEquals("amount", Json.parseToJsonElement(json).jsonObject["error"]!!.jsonObject["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
        assertEquals(r, Json.decodeFromString(serializer, json))
    }

    @Test
    fun `envelope omits default fields and decodes them back as defaults`() {
        val r = ApiResponse<String>(success = true, meta = Meta("req-5", "t"))
        val serializer = ApiResponse.serializer(kotlinx.serialization.serializer<String>())

        val json = Json.encodeToString(serializer, r)

        assertEquals(setOf("success", "meta"), Json.parseToJsonElement(json).jsonObject.keys)
        assertEquals(r, Json.decodeFromString(serializer, json))
        assertEquals(Pagination(), Json.decodeFromString(Pagination.serializer(), "{}"))
        assertEquals(ApiError("A", "m"), Json.decodeFromString(ApiError.serializer(), """{"code":"A","message":"m"}"""))
    }

    @Test
    fun `envelope with encodeDefaults writes every field as the API does`() {
        val wire = Json { encodeDefaults = true }
        val r = ApiResponse<String>(success = true, pagination = Pagination(), meta = Meta("req-6", "t"),
            error = ApiError("A", "m"))
        val serializer = ApiResponse.serializer(kotlinx.serialization.serializer<String>())

        val obj = Json.parseToJsonElement(wire.encodeToString(serializer, r)).jsonObject

        assertEquals(setOf("success", "data", "error", "pagination", "meta"), obj.keys)
        assertEquals(setOf("cursor", "hasMore", "total"), obj["pagination"]!!.jsonObject.keys)
        assertEquals(setOf("code", "message", "details"), obj["error"]!!.jsonObject.keys)
    }

    @Test
    fun `meta and api error require their fields on decode`() {
        assertFailsWith<kotlinx.serialization.SerializationException> { Json.decodeFromString(Meta.serializer(), """{"requestId":"r"}""") }
        assertFailsWith<kotlinx.serialization.SerializationException> { Json.decodeFromString(ApiError.serializer(), """{"message":"m"}""") }
    }

    @Test
    fun `pagination decodes partially specified fields`() {
        assertEquals(Pagination(cursor = "c"), Json.decodeFromString(Pagination.serializer(), """{"cursor":"c"}"""))
        assertEquals(Pagination(hasMore = true), Json.decodeFromString(Pagination.serializer(), """{"hasMore":true}"""))
        assertEquals(Pagination(total = 7), Json.decodeFromString(Pagination.serializer(), """{"total":7}"""))
        assertEquals("""{"total":7}""", Json.encodeToString(Pagination.serializer(), Pagination(total = 7)))
    }

    @Test
    fun `envelope without meta fails to decode`() {
        val serializer = ApiResponse.serializer(kotlinx.serialization.serializer<String>())
        assertFailsWith<kotlinx.serialization.SerializationException> { Json.decodeFromString(serializer, """{"success":true}""") }
    }

    @Test
    fun `well-known api errors have stable codes`() {
        val codes = listOf(
            ApiError.TOKEN_EXPIRED, ApiError.TOKEN_INVALID, ApiError.INSUFFICIENT_SCOPE, ApiError.SESSION_REVOKED,
            ApiError.USER_NOT_FOUND, ApiError.CONNECTION_NOT_FOUND, ApiError.OAUTH_FAILED, ApiError.TRANSACTION_NOT_FOUND,
            ApiError.SUBSCRIPTION_NOT_FOUND, ApiError.BILL_NOT_FOUND, ApiError.SYNC_IN_PROGRESS, ApiError.INVALID_FINGERPRINT,
            ApiError.BATCH_TOO_LARGE, ApiError.RAW_TEXT_REJECTED, ApiError.RATE_LIMITED, ApiError.INTERNAL_ERROR
        ).map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals("SERVER_001", ApiError.INTERNAL_ERROR.code)
        assertNull(ApiError.TOKEN_EXPIRED.details)
    }

    // ── Repository exceptions ──

    @Test
    fun `repository exception keeps its message and cause`() {
        val cause = IllegalStateException("db down")
        val e = RepositoryException("save failed", cause)
        assertEquals("save failed", e.message); assertSame(cause, e.cause)
        assertNull(RepositoryException("no cause").cause)
    }

    @Test
    fun `not found and duplicate exceptions are repository exceptions`() {
        assertIs<RepositoryException>(EntityNotFoundException("user 1"))
        val dup = DuplicateEntityException("fingerprint abc")
        assertIs<RepositoryException>(dup)
        assertEquals("fingerprint abc", dup.message)
    }

    // ── workflow data types ──

    @Test
    fun `transaction data defaults to an INR other-category transaction`() {
        val t = TransactionData(userId = "u", amount = BigDecimal("99.00"), merchantName = "Zepto", transactedAt = Instant.EPOCH)
        assertEquals("INR", t.currency); assertEquals("other", t.category)
        assertNull(t.merchantRaw); assertNull(t.accountLast4); assertNull(t.paymentMethod); assertNull(t.transactionId)
    }

    @Test
    fun `gmail signals default to no emails`() {
        val g = GmailSignals()
        assertEquals(0, g.loginEmailCount); assertEquals(0, g.usageEmailCount)
        assertNull(g.lastLoginEmail); assertNull(g.lastUsageEmail)
        assertEquals(3, GmailSignals(loginEmailCount = 3, lastLoginEmail = Instant.EPOCH).loginEmailCount)
    }

    @Test
    fun `subscription data carries its fields`() {
        val s = SubscriptionData(
            id = "s1", userId = "u", name = "Hotstar", merchantName = "HOTSTAR", amount = BigDecimal("299"),
            billingCycle = "monthly", firstBilledDate = Instant.EPOCH, lastBilledDate = Instant.EPOCH, status = "active"
        )
        assertEquals("Hotstar", s.name); assertEquals(s, s.copy())
    }

    @Test
    fun `api error details are optional json`() {
        assertEquals(JsonPrimitive("x"), ApiError("C", "m", buildJsonObject { put("k", "x") }).details!!["k"])
    }

    @Test
    fun `api error without details omits them in compact json`() {
        assertEquals("""{"code":"C","message":"m"}""", Json.encodeToString(ApiError.serializer(), ApiError("C", "m")))
    }
}
