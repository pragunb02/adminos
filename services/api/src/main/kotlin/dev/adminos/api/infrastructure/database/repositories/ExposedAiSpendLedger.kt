package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ai.AiSpendLedger
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.dbQueryReadCommitted
import dev.adminos.api.infrastructure.database.tables.AiSpendTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.YearMonth

/**
 * The monthly AI spend ledger on Postgres (AOS-702). [reserve] runs in one transaction: create the month's
 * row if missing (`ON CONFLICT DO NOTHING`), lock it (`FOR UPDATE`), and add the reservation only if it fits
 * under the cap. Concurrent calls and API instances are serialised on that row, so the cap can't be overshot.
 */
class ExposedAiSpendLedger(private val now: () -> Instant = Instant::now) : AiSpendLedger {

    override suspend fun reserve(month: YearMonth, micros: Long, capMicros: Long): Boolean = dbQueryReadCommitted {
        val t = AiSpendTable
        val key = month.toString()
        t.insertIgnore {
            it[t.month] = key
            it[spentMicros] = 0
            it[reservedMicros] = 0
            it[updatedAt] = now()
        }
        val row = t.selectAll().where { t.month eq key }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).single()
        val spent = row[t.spentMicros]
        val reserved = row[t.reservedMicros]
        if (spent + reserved + micros > capMicros) return@dbQueryReadCommitted false
        t.update({ t.month eq key }) {
            it[reservedMicros] = reserved + micros
            it[updatedAt] = now()
        }
        true
    }

    override suspend fun settle(month: YearMonth, reservedMicros: Long, actualMicros: Long): Unit = dbQueryReadCommitted {
        val t = AiSpendTable
        val key = month.toString()
        val row = t.selectAll().where { t.month eq key }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).singleOrNull()
            ?: error("No AI spend row for $key: settle is only called after reserve")
        t.update({ t.month eq key }) {
            it[spentMicros] = row[t.spentMicros] + actualMicros
            it[t.reservedMicros] = maxOf(0, row[t.reservedMicros] - reservedMicros)
            it[updatedAt] = now()
        }
    }

    override suspend fun spent(month: YearMonth): Long = dbQuery {
        AiSpendTable.selectAll().where { AiSpendTable.month eq month.toString() }.singleOrNull()?.get(AiSpendTable.spentMicros) ?: 0L
    }
}
