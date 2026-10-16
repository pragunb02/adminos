package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.scheduling.JobRunRepository
import dev.adminos.api.domain.scheduling.JobRunRepositoryContract
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import org.junit.jupiter.api.Tag

/** The same claim rules on real Postgres: unique (job, slot), row locks, JSONB results (AOS-501). */
@Tag("integration")
class JobRunRepositoryIntegrationTest : JobRunRepositoryContract() {
    override fun repository(): JobRunRepository {
        PostgresTestDatabase.ensureStarted()
        return ExposedJobRunRepository()
    }
}
