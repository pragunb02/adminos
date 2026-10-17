package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.financial.trips.*
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.TravelBookingsTable as B
import dev.adminos.api.infrastructure.database.tables.TripEditsTable as E
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

/** Postgres [TravelBookingRepository] (V52, AOS-910). The Gmail worker writes the same table with the same merge rules. */
class ExposedTravelBookingRepository : TravelBookingRepository {
    override suspend fun upsert(booking: TravelBooking): TravelBooking = dbQuery {
        val known = B.selectAll().where { (B.userId eq booking.userId) and (B.brand eq booking.brand) and (B.refHash eq booking.refHash) }
            .singleOrNull()?.toBooking()
        if (known == null) {
            B.insert {
                it[id] = booking.id; it[userId] = booking.userId; it[bookingSource] = booking.source.name.lowercase(); it[sourceRef] = booking.sourceRef
                it[brand] = booking.brand; it[mode] = booking.mode.name.lowercase(); it[refHash] = booking.refHash
                it[journeyDate] = booking.journeyDate; it[bookedAt] = booking.bookedAt; it[amount] = booking.amount
                it[cancelled] = booking.cancelled; it[createdAt] = Instant.now(); it[updatedAt] = Instant.now()
            }
            booking
        } else {
            val merged = known.copy(journeyDate = known.journeyDate ?: booking.journeyDate, amount = known.amount ?: booking.amount,
                bookedAt = minOf(known.bookedAt, booking.bookedAt), cancelled = known.cancelled || booking.cancelled)
            B.update({ B.id eq known.id }) {
                it[journeyDate] = merged.journeyDate; it[amount] = merged.amount; it[bookedAt] = merged.bookedAt
                it[cancelled] = merged.cancelled; it[updatedAt] = Instant.now()
            }
            merged
        }
    }

    override suspend fun findByUser(userId: UUID, range: TimeRange): List<TravelBooking> = dbQuery {
        B.selectAll().where { (B.userId eq userId) and (B.bookedAt greaterEq range.from) and (B.bookedAt less range.until) }
            .orderBy(B.bookedAt to SortOrder.ASC).map { it.toBooking() }
    }

    private fun ResultRow.toBooking() = TravelBooking(
        id = this[B.id], userId = this[B.userId], source = BookingSource.valueOf(this[B.bookingSource].uppercase()), sourceRef = this[B.sourceRef],
        brand = this[B.brand], mode = TravelMode.valueOf(this[B.mode].uppercase()), refHash = this[B.refHash], journeyDate = this[B.journeyDate],
        bookedAt = this[B.bookedAt], amount = this[B.amount], cancelled = this[B.cancelled]
    )
}

/** Postgres [TripEditRepository] (V52). */
class ExposedTripEditRepository : TripEditRepository {
    override suspend fun findByUser(userId: UUID): List<TripEdit> = dbQuery {
        E.selectAll().where { E.userId eq userId }.map {
            TripEdit(it[E.tripId], it[E.userId], it[E.name], it[E.dismissed], ids(it[E.addedIds]), ids(it[E.removedIds]))
        }
    }

    override suspend fun save(edit: TripEdit): TripEdit = dbQuery {
        val changed = E.update({ (E.tripId eq edit.tripId) and (E.userId eq edit.userId) }) { write(it, edit) }
        if (changed == 0) E.insert { it[tripId] = edit.tripId; it[userId] = edit.userId; write(it, edit) }
        edit
    }

    private fun write(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, e: TripEdit) {
        it[E.name] = e.name
        it[E.dismissed] = e.dismissed
        it[E.addedIds] = e.added.sorted().joinToString(",").ifEmpty { null }
        it[E.removedIds] = e.removed.sorted().joinToString(",").ifEmpty { null }
        it[E.updatedAt] = Instant.now()
    }

    private fun ids(raw: String?): Set<UUID> = raw.orEmpty().split(',').filter { it.isNotBlank() }.map(UUID::fromString).toSet()
}
