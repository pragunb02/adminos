package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.scheduling.ClaimRules
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.JobRun
import dev.adminos.api.domain.scheduling.JobRunRepository
import dev.adminos.api.domain.scheduling.JobRunStatus
import dev.adminos.api.domain.scheduling.RunOutcome
import dev.adminos.api.domain.scheduling.SlotClaim
import dev.adminos.api.infrastructure.database.JsonColumns
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.JobRunsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Postgres [JobRunRepository] (AOS-501). [claim] runs in one transaction:
 * 1. `INSERT … ON CONFLICT DO NOTHING` — the first instance to reach a new slot wins it;
 * 2. otherwise lock the existing row (`FOR UPDATE`) and apply the shared retry rule ([ClaimRules]),
 *    so two instances retrying the same slot are serialized and only one gets it.
 */
class ExposedJobRunRepository : JobRunRepository {

    override suspend fun claim(claim: SlotClaim): JobRun? = dbQuery {
        val t = JobRunsTable
        val newId = UUID.randomUUID()
        val inserted = t.insertIgnore {
            it[id] = newId
            it[jobName] = claim.job.value
            it[slot] = claim.slot
            it[status] = JobRunStatus.RUNNING.name.lowercase()
            it[attempts] = 1
            it[startedAt] = claim.now
            it[leaseUntil] = claim.leaseUntil
        }.insertedCount
        if (inserted == 1) return@dbQuery findById(newId)

        val existing = t.selectAll()
            .where { (t.jobName eq claim.job.value) and (t.slot eq claim.slot) }
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate())
            .single().toJobRun()
        if (!ClaimRules.canRetry(existing, claim)) return@dbQuery null

        t.update({ t.id eq existing.id }) {
            it[status] = JobRunStatus.RUNNING.name.lowercase()
            it[attempts] = existing.attempts + 1
            it[startedAt] = claim.now
            it[leaseUntil] = claim.leaseUntil
            it[finishedAt] = null
            it[result] = null
            it[error] = null
        }
        findById(existing.id)
    }

    override suspend fun finish(id: UUID, outcome: RunOutcome): Unit = dbQuery {
        val t = JobRunsTable
        // Only a RUNNING row is finished: a late finish from an instance whose lease expired can't overwrite a retry's result.
        t.update({ (t.id eq id) and (t.status eq JobRunStatus.RUNNING.name.lowercase()) }) {
            it[finishedAt] = outcome.finishedAt
            when (outcome) {
                is RunOutcome.Succeeded -> {
                    it[status] = JobRunStatus.SUCCEEDED.name.lowercase()
                    it[result] = JsonColumns.encode(outcome.result.counts)
                }
                is RunOutcome.Failed -> {
                    it[status] = JobRunStatus.FAILED.name.lowercase()
                    it[error] = outcome.error
                }
            }
        }
    }

    override suspend fun latest(job: JobName): JobRun? = dbQuery {
        JobRunsTable.selectAll().where { JobRunsTable.jobName eq job.value }
            .orderBy(JobRunsTable.slot to SortOrder.DESC)
            .limit(1)
            .singleOrNull()?.toJobRun()
    }

    private fun findById(id: UUID): JobRun =
        JobRunsTable.selectAll().where { JobRunsTable.id eq id }.single().toJobRun()

    private fun ResultRow.toJobRun() = JobRun(
        id = this[JobRunsTable.id],
        job = JobName(this[JobRunsTable.jobName]),
        slot = this[JobRunsTable.slot],
        status = JobRunStatus.valueOf(this[JobRunsTable.status].uppercase()),
        attempts = this[JobRunsTable.attempts],
        startedAt = this[JobRunsTable.startedAt],
        leaseUntil = this[JobRunsTable.leaseUntil],
        finishedAt = this[JobRunsTable.finishedAt],
        result = this[JobRunsTable.result]?.let { text ->
            JobResult(JsonColumns.decode(text).mapValues { (key, v) ->
                (v as? Number)?.toInt() ?: throw IllegalStateException("job_runs.result.$key is not a count: $v")
            })
        },
        error = this[JobRunsTable.error]
    )
}
