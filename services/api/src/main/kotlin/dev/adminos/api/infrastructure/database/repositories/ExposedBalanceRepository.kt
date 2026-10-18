package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.balances.BalanceAccount
import dev.adminos.api.domain.balances.BalanceReading
import dev.adminos.api.domain.balances.BalanceRepository
import dev.adminos.api.domain.balances.BalanceRules
import dev.adminos.api.domain.balances.BalanceSource
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.BalanceReadingsTable as T
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.util.UUID

/**
 * Balance readings on Postgres (AOS-720, V92). The figure is encrypted with [TokenEncryptor] (AES-256-GCM), bound to
 * `balance_readings:<id>:amount`, so a figure copied to another row fails to decrypt.
 */
class ExposedBalanceRepository(private val encryptor: TokenEncryptor) : BalanceRepository {

    override suspend fun save(reading: BalanceReading): Boolean {
        val sealed = encryptor.encrypt(reading.amount.toPlainString(), context(reading.id))
        return dbQuery {
            val inserted = T.insertIgnore {
                it[id] = reading.id
                it[userId] = reading.userId
                it[bankCode] = reading.account.bankCode
                it[accountLast4] = reading.account.last4
                it[sourceKind] = reading.source.key
                it[sourceRef] = reading.sourceRef
                it[amount] = sealed
                it[observedAt] = reading.observedAt
                it[recordedAt] = reading.recordedAt
            }.insertedCount == 1
            if (inserted) prune(reading)
            inserted
        }
    }

    /** Keeps the newest [BalanceRules.KEEP_PER_ACCOUNT] readings of [reading]'s account; only the latest is used. */
    private fun prune(reading: BalanceReading) {
        val sameAccount: Op<Boolean> = (T.userId eq reading.userId) and (T.accountLast4 eq reading.account.last4) and
            (reading.account.bankCode?.let { T.bankCode eq it } ?: T.bankCode.isNull())
        // The same order as BalanceReading.NEWEST_FIRST: 'statement' sorts after 'sms', so DESC puts it first.
        val old = T.select(T.id).where(sameAccount)
            .orderBy(T.observedAt to SortOrder.DESC, T.sourceKind to SortOrder.DESC, T.recordedAt to SortOrder.DESC, T.id to SortOrder.ASC)
            .offset(BalanceRules.KEEP_PER_ACCOUNT.toLong()).map { it[T.id] }
        if (old.isNotEmpty()) T.deleteWhere { (T.userId eq reading.userId) and (T.id inList old) }
    }

    /**
     * A row that can't be read (another key, a tampered or copied figure) is skipped and logged by its id only, so one
     * bad row can't take down the balances and the forecast; it never becomes a figure.
     */
    override suspend fun forUser(userId: UUID): List<BalanceReading> = dbQuery {
        T.selectAll().where { T.userId eq userId }.mapNotNull { it.toReadingOrNull() }
    }.sortedWith(BalanceReading.NEWEST_FIRST)

    private fun ResultRow.toReadingOrNull(): BalanceReading? {
        val id = this[T.id]
        return try {
            BalanceReading(
                id = id, userId = this[T.userId], account = BalanceAccount(this[T.bankCode], this[T.accountLast4]),
                amount = BigDecimal(encryptor.decrypt(this[T.amount], context(id))),
                observedAt = this[T.observedAt], source = BalanceSource.of(this[T.sourceKind]), sourceRef = this[T.sourceRef],
                recordedAt = this[T.recordedAt]
            )
        } catch (e: java.security.GeneralSecurityException) {
            unreadable(id, e)
        } catch (e: IllegalArgumentException) {
            unreadable(id, e)
        }
    }

    private fun unreadable(id: UUID, e: Exception): BalanceReading? {
        log.warn("Balance reading {} can't be read ({}); skipped", id, e.javaClass.simpleName) // never the value
        return null
    }

    private val log = org.slf4j.LoggerFactory.getLogger(ExposedBalanceRepository::class.java)

    private fun context(id: UUID) = "balance_readings:$id:amount"
}
