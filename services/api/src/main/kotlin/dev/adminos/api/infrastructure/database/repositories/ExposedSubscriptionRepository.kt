package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.*
import dev.adminos.api.infrastructure.database.CursorCodec
import dev.adminos.api.infrastructure.database.tables.SubscriptionsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class ExposedSubscriptionRepository : SubscriptionRepository {

    override suspend fun save(subscription: Subscription): Subscription = newSuspendedTransaction {
        SubscriptionsTable.insert {
            it[id] = subscription.id
            it[userId] = subscription.userId
            it[name] = subscription.name
            it[merchantName] = subscription.merchantName
            it[category] = subscription.category.name.lowercase()
            it[amount] = subscription.amount
            it[currency] = subscription.currency
            it[billingCycle] = subscription.billingCycle.name.lowercase()
            it[nextBillingDate] = subscription.nextBillingDate
            it[lastBilledDate] = subscription.lastBilledDate
            it[firstBilledDate] = subscription.firstBilledDate
            it[status] = subscription.status.name.lowercase()
            it[detectionSource] = subscription.detectionSource
            it[usageStatus] = subscription.usageStatus.name.lowercase()
            it[usageSignal] = subscription.usageSignal
            it[wasteScore] = subscription.wasteScore
            it[wasteScoreUpdatedAt] = subscription.wasteScoreUpdatedAt
            it[priceChanged] = subscription.priceChanged
            it[priceChangePct] = subscription.priceChangePct
            it[isFlagged] = subscription.isFlagged
            it[flaggedReason] = subscription.flaggedReason
            it[flaggedAt] = subscription.flaggedAt
            it[flagDismissedAt] = subscription.flagDismissedAt
            it[transactionIds] = subscription.transactionIds.takeIf { ids -> ids.isNotEmpty() }
                ?.joinToString(",") { uuid -> uuid.toString() }
            it[cancellationDraft] = subscription.cancellationDraft
            it[cancellationDraftGeneratedAt] = subscription.cancellationDraftGeneratedAt
            it[metadata] = subscription.metadata?.let { m -> Json.encodeToString(kotlinx.serialization.serializer(), m) }
            it[createdAt] = subscription.createdAt
            it[updatedAt] = subscription.updatedAt
        }
        subscription
    }

    override suspend fun findById(id: UUID): Subscription? = newSuspendedTransaction {
        SubscriptionsTable.select { SubscriptionsTable.id eq id }
            .singleOrNull()?.toSubscription()
    }

    override suspend fun findByUserId(
        userId: UUID, status: SubscriptionStatus?, cursor: String?, limit: Int
    ): List<Subscription> = newSuspendedTransaction {
        val query = SubscriptionsTable.select { SubscriptionsTable.userId eq userId }
        status?.let { s -> query.andWhere { SubscriptionsTable.status eq s.name.lowercase() } }
        cursor?.let { c ->
            val (key, _) = CursorCodec.decode(c)
            val cursorInstant = Instant.parse(key)
            query.andWhere { SubscriptionsTable.createdAt less cursorInstant }
        }
        query.orderBy(SubscriptionsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toSubscription() }
    }

    override suspend fun countByUserId(userId: UUID, status: SubscriptionStatus?): Long = newSuspendedTransaction {
        val query = SubscriptionsTable.select { SubscriptionsTable.userId eq userId }
        status?.let { s -> query.andWhere { SubscriptionsTable.status eq s.name.lowercase() } }
        query.count()
    }

    override suspend fun findByUserAndMerchantAndCycle(
        userId: UUID, merchantName: String, billingCycle: BillingCycle
    ): Subscription? = newSuspendedTransaction {
        SubscriptionsTable.select {
            (SubscriptionsTable.userId eq userId) and
                (SubscriptionsTable.merchantName.lowerCase() eq merchantName.lowercase()) and
                (SubscriptionsTable.billingCycle eq billingCycle.name.lowercase())
        }.singleOrNull()?.toSubscription()
    }

    override suspend fun update(subscription: Subscription): Subscription = newSuspendedTransaction {
        val now = Instant.now()
        SubscriptionsTable.update({ SubscriptionsTable.id eq subscription.id }) {
            it[name] = subscription.name
            it[merchantName] = subscription.merchantName
            it[category] = subscription.category.name.lowercase()
            it[amount] = subscription.amount
            it[billingCycle] = subscription.billingCycle.name.lowercase()
            it[nextBillingDate] = subscription.nextBillingDate
            it[lastBilledDate] = subscription.lastBilledDate
            it[status] = subscription.status.name.lowercase()
            it[usageStatus] = subscription.usageStatus.name.lowercase()
            it[usageSignal] = subscription.usageSignal
            it[wasteScore] = subscription.wasteScore
            it[wasteScoreUpdatedAt] = subscription.wasteScoreUpdatedAt
            it[priceChanged] = subscription.priceChanged
            it[priceChangePct] = subscription.priceChangePct
            it[isFlagged] = subscription.isFlagged
            it[flaggedReason] = subscription.flaggedReason
            it[flaggedAt] = subscription.flaggedAt
            it[flagDismissedAt] = subscription.flagDismissedAt
            it[transactionIds] = subscription.transactionIds.takeIf { ids -> ids.isNotEmpty() }
                ?.joinToString(",") { uuid -> uuid.toString() }
            it[cancellationDraft] = subscription.cancellationDraft
            it[cancellationDraftGeneratedAt] = subscription.cancellationDraftGeneratedAt
            it[updatedAt] = now
        }
        subscription.copy(updatedAt = now)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ResultRow.toSubscription() = Subscription(
        id = this[SubscriptionsTable.id],
        userId = this[SubscriptionsTable.userId],
        name = this[SubscriptionsTable.name],
        merchantName = this[SubscriptionsTable.merchantName],
        category = SubscriptionCategory.valueOf(this[SubscriptionsTable.category].uppercase()),
        amount = this[SubscriptionsTable.amount],
        currency = this[SubscriptionsTable.currency],
        billingCycle = BillingCycle.valueOf(this[SubscriptionsTable.billingCycle].uppercase()),
        nextBillingDate = this[SubscriptionsTable.nextBillingDate],
        lastBilledDate = this[SubscriptionsTable.lastBilledDate],
        firstBilledDate = this[SubscriptionsTable.firstBilledDate],
        status = SubscriptionStatus.valueOf(this[SubscriptionsTable.status].uppercase()),
        detectionSource = this[SubscriptionsTable.detectionSource],
        usageStatus = UsageStatus.valueOf(this[SubscriptionsTable.usageStatus].uppercase()),
        usageSignal = this[SubscriptionsTable.usageSignal],
        wasteScore = this[SubscriptionsTable.wasteScore],
        wasteScoreUpdatedAt = this[SubscriptionsTable.wasteScoreUpdatedAt],
        priceChanged = this[SubscriptionsTable.priceChanged],
        priceChangePct = this[SubscriptionsTable.priceChangePct],
        isFlagged = this[SubscriptionsTable.isFlagged],
        flaggedReason = this[SubscriptionsTable.flaggedReason],
        flaggedAt = this[SubscriptionsTable.flaggedAt],
        flagDismissedAt = this[SubscriptionsTable.flagDismissedAt],
        transactionIds = this[SubscriptionsTable.transactionIds]
            ?.split(",")?.filter { it.isNotBlank() }?.map { UUID.fromString(it.trim()) }
            ?: emptyList(),
        cancellationDraft = this[SubscriptionsTable.cancellationDraft],
        cancellationDraftGeneratedAt = this[SubscriptionsTable.cancellationDraftGeneratedAt],
        metadata = this[SubscriptionsTable.metadata]?.let {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(it) as? Map<String, Any?>
        },
        createdAt = this[SubscriptionsTable.createdAt],
        updatedAt = this[SubscriptionsTable.updatedAt]
    )
}
