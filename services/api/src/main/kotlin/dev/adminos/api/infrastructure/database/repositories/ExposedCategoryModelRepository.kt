package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.learning.CategoryModelRepository
import dev.adminos.api.domain.financial.learning.ModelMode
import dev.adminos.api.domain.financial.learning.StoredCategoryModel
import dev.adminos.api.domain.financial.learning.TrainingSummary
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.CategoryModelsTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/** Users' learned categorisers on Postgres (AOS-707). */
class ExposedCategoryModelRepository : CategoryModelRepository {

    override suspend fun find(userId: UUID): StoredCategoryModel? = dbQuery {
        T.selectAll().where { T.userId eq userId }.singleOrNull()?.toModel()
    }

    override suspend fun findChecksum(userId: UUID): String? = dbQuery {
        T.select(T.checksum).where { T.userId eq userId }.singleOrNull()?.get(T.checksum)
    }

    override suspend fun save(model: StoredCategoryModel) {
        dbQuery {
            T.upsert(T.userId) {
                it[userId] = model.userId
                it[algorithm] = model.algorithm
                it[formatVersion] = model.formatVersion
                it[blob] = model.blob
                it[checksum] = model.checksum
                it[mode] = model.mode.name.lowercase()
                it[trainingRows] = model.summary.trainingRows
                it[corrections] = model.summary.corrections
                it[categories] = model.summary.categories
                it[heldOut] = model.summary.heldOut
                it[heldOutPrecision] = model.summary.heldOutPrecision
                it[calibrationError] = model.summary.calibrationError
                it[temperature] = model.summary.temperature
                it[trainedAt] = model.trainedAt
            }
        }
    }

    override suspend fun delete(userId: UUID): Boolean = dbQuery { T.deleteWhere { T.userId eq userId } > 0 }

    private fun ResultRow.toModel() = StoredCategoryModel(
        userId = this[T.userId],
        algorithm = this[T.algorithm],
        formatVersion = this[T.formatVersion],
        blob = this[T.blob],
        checksum = this[T.checksum],
        mode = ModelMode.valueOf(this[T.mode].uppercase()),
        summary = TrainingSummary(
            trainingRows = this[T.trainingRows],
            corrections = this[T.corrections],
            categories = this[T.categories],
            heldOut = this[T.heldOut],
            heldOutPrecision = this[T.heldOutPrecision],
            calibrationError = this[T.calibrationError],
            temperature = this[T.temperature]
        ),
        trainedAt = this[T.trainedAt]
    )
}
