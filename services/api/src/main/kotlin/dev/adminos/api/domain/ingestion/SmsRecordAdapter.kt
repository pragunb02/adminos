package dev.adminos.api.domain.ingestion

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestSource
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Adapter: structured SMS records from the phone → common intake events (D-21, D-34).
 * The record fingerprint is the `sourceRef`, so resending the same SMS is a duplicate, not a second
 * transaction.
 */
object SmsRecordAdapter {

    /** Phones report local times; India-first default until per-user time zones (AOS-120). */
    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    fun toEvent(userId: UUID, record: SmsRecord, at: String): IngestEvent {
        val amount = BigDecimal(record.amount.toString())
        require(amount.scale() <= 2 || amount.stripTrailingZeros().scale() <= 2) { "$at.amount: at most 2 decimal places" }
        val occurredAt = parseDate(record.date) ?: throw IllegalArgumentException("$at.date: '${record.date}' is not an ISO date/time")
        return IngestEvent(
            userId = userId,
            source = IngestSource.SMS,
            sourceRef = fingerprint(record),
            occurredAt = occurredAt,
            direction = IngestDirection.entries.firstOrNull { it.name.equals(record.direction, ignoreCase = true) }
                ?: throw IllegalArgumentException("$at.direction: '${record.direction}' is not debit or credit"),
            amount = amount.setScale(2, RoundingMode.UNNECESSARY),
            merchantRaw = record.merchant.take(255),
            accountHint = record.accountLast4,
            paymentMethod = record.paymentMethod?.let { pm ->
                PaymentMethod.entries.firstOrNull { it.name.equals(pm, ignoreCase = true) }
                    ?: throw IllegalArgumentException("$at.paymentMethod: '$pm' is not one of ${PaymentMethod.entries.joinToString { it.name.lowercase() }}")
            }
        )
    }

    /** Stable identity of an SMS record: the same message always hashes to the same ref. */
    fun fingerprint(r: SmsRecord): String =
        sha256("sms|${r.merchant.trim().lowercase()}|${BigDecimal(r.amount.toString()).stripTrailingZeros().toPlainString()}|${r.date.trim()}|${r.accountLast4}|${r.direction.lowercase()}")

    private fun parseDate(raw: String): Instant? =
        runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).atZone(DEFAULT_ZONE).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw).atStartOfDay(DEFAULT_ZONE).toInstant() }.getOrNull()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
