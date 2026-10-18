package dev.adminos.api.domain.financial.grouping

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.ai.AiPrivacyGate
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/** One proposed member: a name the user's payments show, and how many payments it has. */
data class ProposedMember(val name: String, val key: String, val payments: Int)

/** "Are these one merchant?": the [canonical] name the merge would keep and every [members] name. */
data class MergeProposal(
    val id: UUID,
    val canonical: ProposedMember,
    val members: List<ProposedMember>,
    val score: Double,
    val evidence: Set<MatchEvidence>
) {
    init {
        require(members.isNotEmpty()) { "A proposal merges at least one other name" }
        require(members.none { it.key == canonical.key }) { "The canonical name isn't its own member" }
    }

    val keys: Set<String> get() = (members.map { it.key } + canonical.key).toSet()
}

/** What answering a proposal did. */
sealed interface MergeDecision {
    data class Merged(val proposal: MergeProposal) : MergeDecision
    data class Kept(val proposal: MergeProposal) : MergeDecision
    /** No such proposal now (answered already, or the payments changed): nothing done. */
    data object NotFound : MergeDecision
}

/**
 * Proposes look-alike merchants as one, and remembers the user's answers (AOS-709, D-75).
 * - **Proposals** are computed when asked, from the last [LOOKBACK_DAYS] of spending, after the aliases
 *   already confirmed and without pairs already rejected, so they are never stale and need no job.
 * - **Groups:** names are taken in order (catalogue brands first, then most payments); each one that
 *   isn't grouped yet collects the others that match **it** (not each other), so chains like A~B~C
 *   don't pull in a C unlike A.
 * - **The kept name** is the catalogue brand if one is in the group, else the most used name.
 * - **Confirming** makes each member an alias of the kept name; **rejecting** remembers every pair.
 * A proposal's id comes from its names, so a stale id (the payments changed) is simply not found.
 */
class MerchantGroupingService(
    private val transactions: TransactionRepository,
    private val aliases: MerchantAliasRepository,
    private val calendar: UserCalendar,
    private val audit: AuditService,
    private val matcher: MerchantMatcher = MerchantMatcher(),
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(MerchantGroupingService::class.java)

    suspend fun proposals(userId: UUID): List<MergeProposal> {
        val zone = calendar.zoneOf(userId)
        val since = calendar.today(userId).minusDays(LOOKBACK_DAYS).atStartOfDay(zone).toInstant()
        val resolver = AliasResolver(aliases.aliases(userId))
        val profiles = profiles(transactions.findDebitsByUserIdSince(userId, since), resolver)
        return group(userId, profiles, aliases.rejections(userId))
    }

    suspend fun confirm(userId: UUID, proposalId: UUID): MergeDecision {
        val proposal = proposals(userId).firstOrNull { it.id == proposalId } ?: return MergeDecision.NotFound
        aliases.merge(userId, proposal.canonical.key, proposal.canonical.name, proposal.members.map { it.key }.toSet(), clock.instant())
        audited(userId, proposal, "merchant.merge")
        logger.info("Merged {} merchant names into one for user {} (proposal {})", proposal.members.size, userId, proposal.id)
        return MergeDecision.Merged(proposal)
    }

    suspend fun reject(userId: UUID, proposalId: UUID): MergeDecision {
        val proposal = proposals(userId).firstOrNull { it.id == proposalId } ?: return MergeDecision.NotFound
        val keys = proposal.keys.sorted()
        aliases.reject(userId, keys.flatMapIndexed { i, a -> keys.drop(i + 1).map { b -> RejectedPair.of(a, b) } }.toSet(), clock.instant())
        audited(userId, proposal, "merchant.keep_apart")
        logger.info("Kept {} merchant names apart for user {} (proposal {})", keys.size, userId, proposal.id)
        return MergeDecision.Kept(proposal)
    }

    /** The answer and the names it covered (merchant names only: no amounts, no UPI IDs). */
    private fun audited(userId: UUID, proposal: MergeProposal, action: String) = audit.log(
        userId = userId, actor = ActorType.USER, action = action, entityType = "merchant_merge", entityId = proposal.id,
        afterState = JsonObject(mapOf(
            "kept" to JsonPrimitive(proposal.canonical.name),
            "names" to JsonArray(proposal.members.map { JsonPrimitive(it.name) })
        ))
    )

    /** One profile per merchant after aliases: the most used name for it, its payments and UPI IDs. */
    internal fun profiles(spends: List<Transaction>, resolver: AliasResolver): List<MerchantProfile> =
        spends.mapNotNull { t -> resolver.key(t.merchantName)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (key, txns) ->
                val name = resolver.canonicalNameOf(key)
                    ?: txns.mapNotNull { it.merchantName?.trim() }.groupingBy { it }.eachCount().maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).key
                val clean = MerchantNameCleaner.clean(name) ?: return@mapNotNull null
                val brand = catalogue.byBrand(name)?.brand
                MerchantProfile(name = name, key = key, clean = clean, payments = txns.size,
                    upiIds = txns.mapNotNull { it.upiVpa?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }.toSet(),
                    brand = brand, business = brand != null || txns.any(AiPrivacyGate::isBusiness))
            }

    internal fun group(userId: UUID, profiles: List<MerchantProfile>, rejected: Set<RejectedPair>): List<MergeProposal> {
        val order = profiles.sortedWith(compareBy<MerchantProfile> { it.brand == null }.thenByDescending { it.payments }.thenBy { it.name.length }.thenBy { it.key })
            .take(MAX_MERCHANTS)
        // Only names that can match are compared: the same start (what the matcher needs for a look-alike or the
        // same cleaned name) or a shared UPI ID. Keeps a long merchant list from turning into n² comparisons.
        val position = order.withIndex().associate { (i, p) -> p.key to i }
        val byStart = order.groupBy { matcher.blockKey(it.clean) }
        val byUpi = order.flatMap { p -> p.upiIds.map { it to p } }.groupBy({ it.first }, { it.second })
        fun candidates(lead: MerchantProfile) = (byStart[matcher.blockKey(lead.clean)].orEmpty() + lead.upiIds.flatMap { byUpi[it].orEmpty() })
            .distinctBy { it.key }.sortedBy { position.getValue(it.key) }
        val grouped = mutableSetOf<String>()
        val proposals = mutableListOf<MergeProposal>()
        for (lead in order) {
            if (lead.key in grouped) continue
            val matches = candidates(lead).filter { it.key !in grouped && it.key != lead.key && RejectedPair.of(lead.key, it.key) !in rejected }
                .mapNotNull { other -> matcher.match(lead, other)?.let { other to it } }
            if (matches.isEmpty()) continue
            val all = listOf(lead) + matches.map { it.first }
            grouped += all.map { it.key }
            val kept = all.firstOrNull { it.brand != null } ?: lead
            proposals += MergeProposal(
                id = idOf(userId, all.map { it.key }),
                canonical = ProposedMember(kept.brand ?: kept.name, kept.key, kept.payments),
                members = all.filter { it.key != kept.key }.map { ProposedMember(it.name, it.key, it.payments) },
                score = matches.minOf { it.second.score },
                evidence = matches.flatMap { it.second.evidence }.toSet()
            )
        }
        return proposals
    }

    companion object {
        /** The same look-back as subscription detection (D-65), so yearly plans are seen. */
        const val LOOKBACK_DAYS = 400L
        /** Most-used merchants considered per request (a person has a few hundred; this bounds a hostile import). */
        const val MAX_MERCHANTS = 1000

        fun idOf(userId: UUID, keys: Collection<String>): UUID = UUID.nameUUIDFromBytes((listOf(userId.toString()) + keys.sorted()).joinToString("|").toByteArray())
    }
}
