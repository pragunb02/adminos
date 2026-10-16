package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V17__job_runs.sql (AOS-501). */
object JobRunsTable : Table("job_runs") {
    val id = javaUUID("id")
    val jobName = varchar("job_name", 63)
    val slot = timestamp("slot")
    val status = varchar("status", 16)
    val attempts = integer("attempts")
    val startedAt = timestamp("started_at")
    val leaseUntil = timestamp("lease_until")
    val finishedAt = timestamp("finished_at").nullable()
    val result = text("result").nullable() // JSONB, written through JsonColumns
    val error = text("error").nullable()
    override val primaryKey = PrimaryKey(id)
}
