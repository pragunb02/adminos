package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.documents.*
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.DocumentsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Postgres [DocumentRepository] (AOS-507). */
class ExposedDocumentRepository : DocumentRepository {
    private val t = DocumentsTable

    override suspend fun save(document: Document): Document = dbQuery {
        t.insert {
            it[id] = document.id
            it[userId] = document.userId
            it[sizeBytes] = document.sizeBytes
            it[sha256] = document.sha256
            it[wrappedKey] = document.wrappedKey
            it[storageKey] = document.storageKey
            it[fileType] = document.fileType.name.lowercase()
            it[originalName] = document.originalName
            it[createdAt] = document.createdAt
            it[updatedAt] = document.updatedAt
            editable(it, document)
        }
        document
    }

    override suspend fun update(document: Document): Document = dbQuery {
        val now = Instant.now()
        t.update({ t.id eq document.id }) {
            editable(it, document)
            it[updatedAt] = now
        }
        document.copy(updatedAt = now)
    }

    override suspend fun findById(id: UUID): Document? = dbQuery { t.selectAll().where { t.id eq id }.singleOrNull()?.toDocument() }

    override suspend fun findByUserId(userId: UUID): List<Document> = dbQuery {
        t.selectAll().where { t.userId eq userId }.orderBy(t.createdAt to SortOrder.DESC).map { it.toDocument() }
    }

    override suspend fun delete(id: UUID): Unit = dbQuery { t.deleteWhere { t.id eq id } }

    override suspend fun findExpiringBetween(from: LocalDate, to: LocalDate): List<Document> = dbQuery {
        t.selectAll().where { (t.expiresOn greaterEq from) and (t.expiresOn lessEq to) }.orderBy(t.expiresOn to SortOrder.ASC).map { it.toDocument() }
    }

    /** Only what a user may change after upload. */
    private fun editable(it: UpdateBuilder<*>, d: Document) {
        it[t.title] = d.title
        it[t.category] = d.category.name.lowercase()
        it[t.expiresOn] = d.expiresOn
        it[t.notes] = d.notes
        it[t.expiryRemindersSent] = encodeDays(d.expiryRemindersSent)
    }

    private fun ResultRow.toDocument() = Document(
        id = this[t.id], userId = this[t.userId], title = this[t.title],
        category = DocumentCategory.valueOf(this[t.category].uppercase()), fileType = DocumentFileType.valueOf(this[t.fileType].uppercase()),
        sizeBytes = this[t.sizeBytes], sha256 = this[t.sha256], wrappedKey = this[t.wrappedKey], storageKey = this[t.storageKey],
        originalName = this[t.originalName], expiresOn = this[t.expiresOn], notes = this[t.notes],
        expiryRemindersSent = decodeDays(this[t.expiryRemindersSent]) ?: emptySet(),
        createdAt = this[t.createdAt], updatedAt = this[t.updatedAt]
    )
}
