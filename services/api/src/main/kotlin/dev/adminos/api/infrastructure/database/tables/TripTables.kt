package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V40__trips.sql (AOS-910). The Gmail worker writes bookings too. */
object TravelBookingsTable : Table("travel_bookings") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val bookingSource = varchar("source", 10)
    val sourceRef = varchar("source_ref", 255)
    val brand = varchar("brand", 40)
    val mode = varchar("mode", 10)
    val refHash = char("ref_hash", 64)
    val journeyDate = date("journey_date").nullable()
    val bookedAt = timestamp("booked_at")
    val amount = decimal("amount", 12, 2).nullable()
    val cancelled = bool("cancelled")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object TripEditsTable : Table("trip_edits") {
    val tripId = javaUUID("trip_id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val name = varchar("name", 80).nullable()
    val dismissed = bool("dismissed")
    val addedIds = text("added_ids").nullable()
    val removedIds = text("removed_ids").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(tripId)
}
