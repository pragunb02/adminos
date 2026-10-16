package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.links.MatchCandidate
import dev.adminos.api.domain.financial.links.TransactionLinkRepository
import dev.adminos.api.domain.financial.links.TransactionMatcher

/** Candidates = same user, type and exact amount within the matcher's hard limit, with their merged sources. */
class RepositoryMatchCandidates(
    private val transactions: TransactionRepository,
    private val links: TransactionLinkRepository
) : MatchCandidateSource {
    override suspend fun candidatesFor(incoming: Transaction): List<MatchCandidate> {
        val limit = TransactionMatcher.HARD_LIMIT
        val found = transactions.findMatchCandidates(
            incoming.userId, incoming.type, incoming.amount, incoming.transactedAt.minus(limit), incoming.transactedAt.plus(limit)
        )
        val merged = links.mergedSources(found.map { it.id })
        return found.map { MatchCandidate(it, merged[it.id].orEmpty()) }
    }
}
