package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiRefusal
import dev.adminos.api.domain.ai.AiResult
import java.util.UUID

/**
 * The categoriser's client on the AI port (AOS-702): every question goes through [AiGateway] with
 * [CategorizeMerchantTask], so it is flagged, redacted, capped, circuit-broken and audited like any AI call.
 * The results map onto AOS-512's three outcomes, which the service and its cache already handle:
 * - answered → [AiCategoryResult.Answered];
 * - rejected → [AiCategoryResult.Rejected] (cached: not asked again);
 * - withheld by the redactor → [AiCategoryResult.Withheld] (never cached: the cleaned cache key is shared with the real merchant);
 * - unavailable, feature off or cap reached → [AiCategoryResult.Unavailable] (not cached: asked again later).
 */
class PortAiCategoryClient(private val gateway: AiGateway) : AiCategoryClient {

    override suspend fun ask(userId: UUID, question: AiCategoryQuestion): AiCategoryResult =
        when (val result = gateway.run(CategorizeMerchantTask, userId, question)) {
            is AiResult.Answered -> AiCategoryResult.Answered(result.value, result.model)
            is AiResult.Rejected -> AiCategoryResult.Rejected(result.code.value)
            is AiResult.Unavailable -> AiCategoryResult.Unavailable(result.code.value)
            is AiResult.Refused -> when (result.reason) {
                AiRefusal.WITHHELD -> AiCategoryResult.Withheld(result.reason.code.value)
                AiRefusal.FEATURE_OFF, AiRefusal.CAP_REACHED -> AiCategoryResult.Unavailable(result.reason.code.value)
            }
        }
}
