package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.Transaction as DomainTransaction
import dev.adminos.api.domain.financial.*
import dev.adminos.api.infrastructure.database.CursorCodec
import dev.adminos.api.infrastructure.database.tables.TransactionsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ExposedTransactionRepository : TransactionRepository {

    override suspend fun save(txn: DomainTransaction): DomainTransaction = newSuspendedTransaction {
        TransactionsTable.insert {
            it[id] = txn.id
            it[userId] = txn.userId
            it[sourceType] = txn.sourceType
            it[sourceRef] = txn.sourceRef
            it[type] = txn.type.name.lowercase()
            it[amount] = txn.amount
            it[currency] = txn.currency
            it[merchantName] = txn.merchantName
            it[merchantRaw] = txn.merchantRaw
            it[merchantCategory] = txn.merchantCategory
            it[accountId] = txn.accountId
            it[accountLast4] = txn.accountLast4
            it[paymentMethod] = txn.paymentMethod?.name?.lowercase()
            it[upiVpa] = txn.upiVpa
            it[category] = txn.category.name.lowercase()
            it[subcategory] = txn.subcategory
            it[categorySource] = txn.categorySource
            it[isRecurring] = txn.isRecurring
            it[recurringGroupId] = txn.recurringGroupId
            it[status] = txn.status.name.lowercase()
            it[isAnomaly] = txn.isAnomaly
            it[anomalyId] = txn.anomalyId
            it[isVerified] = txn.isVerified
            it[rawEmailId] = txn.rawEmailId
            it[metadata] = txn.metadata?.let { m -> Json.encodeToString(kotlinx.serialization.serializer(), m) }
            it[transactedAt] = txn.transactedAt
            it[createdAt] = txn.createdAt
            it[updatedAt] = txn.updatedAt
        }
        txn
    }

    override suspend fun findById(id: UUID): DomainTransaction? = newSuspendedTransaction {
        TransactionsTable.select { TransactionsTable.id eq id }
            .singleOrNull()?.toTransaction()
    }

    override suspend fun findByUserId(
        userId: UUID, from: Instant?, to: Instant?,
        category: TransactionCategory?, type: TransactionType?,
        accountId: UUID?, isRecurring: Boolean?,
        merchantSearch: String?, cursor: String?, limit: Int
    ): List<DomainTransaction> = newSuspendedTransaction {
        val query = TransactionsTable.select { TransactionsTable.userId eq userId }
        from?.let { f -> query.andWhere { TransactionsTable.transactedAt greaterEq f } }
        to?.let { t -> query.andWhere { TransactionsTable.transactedAt lessEq t } }
        category?.let { c -> query.andWhere { TransactionsTable.category eq c.name.lowercase() } }
        type?.let { t -> query.andWhere { TransactionsTable.type eq t.name.lowercase() } }
        accountId?.let { a -> query.andWhere { TransactionsTable.accountId eq a } }
        isRecurring?.let { r -> query.andWhere { TransactionsTable.isRecurring eq r } }
        merchantSearch?.let { q ->
            val escaped = q.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "%${escaped}%"
            query.andWhere {
                object : Op<Boolean>() {
                    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                        TransactionsTable.merchantName.lowerCase().toQueryBuilder(queryBuilder)
                        queryBuilder.append(" LIKE ")
                        queryBuilder.registerArgument(VarCharColumnType(), pattern)
                        queryBuilder.append(" ESCAPE '\\'")
                    }
                }
            }
        }
        cursor?.let { c ->
            val (key, _) = CursorCodec.decode(c)
            val cursorInstant = Instant.parse(key)
            query.andWhere { TransactionsTable.transactedAt less cursorInstant }
        }
        query.orderBy(TransactionsTable.transactedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toTransaction() }
    }

    override suspend fun countByUserId(
        userId: UUID, from: Instant?, to: Instant?,
        category: TransactionCategory?, type: TransactionType?,
        accountId: UUID?, isRecurring: Boolean?, merchantSearch: String?
    ): Long = newSuspendedTransaction {
        val query = TransactionsTable.select { TransactionsTable.userId eq userId }
        from?.let { f -> query.andWhere { TransactionsTable.transactedAt greaterEq f } }
        to?.let { t -> query.andWhere { TransactionsTable.transactedAt lessEq t } }
        category?.let { c -> query.andWhere { TransactionsTable.category eq c.name.lowercase() } }
        type?.let { t -> query.andWhere { TransactionsTable.type eq t.name.lowercase() } }
        accountId?.let { a -> query.andWhere { TransactionsTable.accountId eq a } }
        isRecurring?.let { r -> query.andWhere { TransactionsTable.isRecurring eq r } }
        merchantSearch?.let { q ->
            val escaped = q.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "%${escaped}%"
            query.andWhere {
                object : Op<Boolean>() {
                    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                        TransactionsTable.merchantName.lowerCase().toQueryBuilder(queryBuilder)
                        queryBuilder.append(" LIKE ")
                        queryBuilder.registerArgument(VarCharColumnType(), pattern)
                        queryBuilder.append(" ESCAPE '\\'")
                    }
                }
            }
        }
        query.count()
    }

    override suspend fun update(txn: DomainTransaction): DomainTransaction = newSuspendedTransaction {
        val now = Instant.now()
        TransactionsTable.update({ TransactionsTable.id eq txn.id }) {
            it[type] = txn.type.name.lowercase()
            it[amount] = txn.amount
            it[merchantName] = txn.merchantName
            it[category] = txn.category.name.lowercase()
            it[subcategory] = txn.subcategory
            it[categorySource] = txn.categorySource
            it[isRecurring] = txn.isRecurring
            it[recurringGroupId] = txn.recurringGroupId
            it[status] = txn.status.name.lowercase()
            it[isAnomaly] = txn.isAnomaly
            it[anomalyId] = txn.anomalyId
            it[isVerified] = txn.isVerified
            it[updatedAt] = now
        }
        txn.copy(updatedAt = now)
    }

    override suspend fun findDebitsByUserIdSince(userId: UUID, since: Instant): List<DomainTransaction> =
        newSuspendedTransaction {
            TransactionsTable.select {
                (TransactionsTable.userId eq userId) and
                    (TransactionsTable.type eq TransactionType.DEBIT.name.lowercase()) and
                    (TransactionsTable.transactedAt greaterEq since)
            }.orderBy(TransactionsTable.transactedAt, SortOrder.ASC)
                .map { it.toTransaction() }
        }

    override suspend fun spendingByCategory(
        userId: UUID, from: Instant, to: Instant
    ): Map<TransactionCategory, BigDecimal> = newSuspendedTransaction {
        TransactionsTable.slice(TransactionsTable.category, TransactionsTable.amount.sum())
            .select {
                (TransactionsTable.userId eq userId) and
                    (TransactionsTable.type eq TransactionType.DEBIT.name.lowercase()) and
                    (TransactionsTable.transactedAt greaterEq from) and
                    (TransactionsTable.transactedAt lessEq to)
            }
            .groupBy(TransactionsTable.category)
            .associate {
                TransactionCategory.valueOf(it[TransactionsTable.category].uppercase()) to
                    (it[TransactionsTable.amount.sum()] ?: BigDecimal.ZERO)
            }
    }

    override suspend fun getCategoryAverage(
        userId: UUID, category: String, days: Int
    ): BigDecimal = newSuspendedTransaction {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val cat = try { TransactionCategory.valueOf(category.uppercase()).name.lowercase() } catch (_: Exception) { null }
        val query = TransactionsTable.select {
            (TransactionsTable.userId eq userId) and
                (TransactionsTable.type eq TransactionType.DEBIT.name.lowercase()) and
                (TransactionsTable.transactedAt greaterEq since)
        }
        cat?.let { c -> query.andWhere { TransactionsTable.category eq c } }
        val txns = query.map { it[TransactionsTable.amount] }
        if (txns.isEmpty()) return@newSuspendedTransaction BigDecimal.ZERO
        val total = txns.fold(BigDecimal.ZERO) { acc, a -> acc + a }
        total.divide(BigDecimal(txns.size), 2, RoundingMode.HALF_UP)
    }

    override suspend fun getRecentByMerchant(
        userId: UUID, merchantName: String, hours: Int
    ): List<DomainTransaction> = newSuspendedTransaction {
        val since = Instant.now().minus(hours.toLong(), ChronoUnit.HOURS)
        TransactionsTable.select {
            (TransactionsTable.userId eq userId) and
                (TransactionsTable.merchantName.lowerCase() eq merchantName.lowercase()) and
                (TransactionsTable.transactedAt greaterEq since)
        }.orderBy(TransactionsTable.transactedAt, SortOrder.DESC)
            .map { it.toTransaction() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ResultRow.toTransaction() = DomainTransaction(
        id = this[TransactionsTable.id],
        userId = this[TransactionsTable.userId],
        sourceType = this[TransactionsTable.sourceType],
        sourceRef = this[TransactionsTable.sourceRef],
        type = TransactionType.valueOf(this[TransactionsTable.type].uppercase()),
        amount = this[TransactionsTable.amount],
        currency = this[TransactionsTable.currency],
        merchantName = this[TransactionsTable.merchantName],
        merchantRaw = this[TransactionsTable.merchantRaw],
        merchantCategory = this[TransactionsTable.merchantCategory],
        accountId = this[TransactionsTable.accountId],
        accountLast4 = this[TransactionsTable.accountLast4],
        paymentMethod = this[TransactionsTable.paymentMethod]?.let { PaymentMethod.valueOf(it.uppercase()) },
        upiVpa = this[TransactionsTable.upiVpa],
        category = TransactionCategory.valueOf(this[TransactionsTable.category].uppercase()),
        subcategory = this[TransactionsTable.subcategory],
        categorySource = this[TransactionsTable.categorySource],
        isRecurring = this[TransactionsTable.isRecurring],
        recurringGroupId = this[TransactionsTable.recurringGroupId],
        status = TransactionStatus.valueOf(this[TransactionsTable.status].uppercase()),
        isAnomaly = this[TransactionsTable.isAnomaly],
        anomalyId = this[TransactionsTable.anomalyId],
        isVerified = this[TransactionsTable.isVerified],
        rawEmailId = this[TransactionsTable.rawEmailId],
        metadata = this[TransactionsTable.metadata]?.let {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(it) as? Map<String, Any?>
        },
        transactedAt = this[TransactionsTable.transactedAt],
        createdAt = this[TransactionsTable.createdAt],
        updatedAt = this[TransactionsTable.updatedAt]
    )
}
