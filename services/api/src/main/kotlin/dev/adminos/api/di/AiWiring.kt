package dev.adminos.api.di

import dev.adminos.api.config.AiConfig
import dev.adminos.api.config.AiFeatureSetting
import dev.adminos.api.domain.ai.AiCallLogRepository
import dev.adminos.api.domain.ai.AiCircuitBreaker
import dev.adminos.api.domain.ai.AiConsentCheck
import dev.adminos.api.domain.ai.AiConsentRepository
import dev.adminos.api.domain.ai.AiConsents
import dev.adminos.api.domain.ai.AiFeaturesService
import dev.adminos.api.domain.ai.AiTask
import dev.adminos.api.domain.ai.InMemoryAiConsentRepository
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiProvider
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiSpendLedger
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.ai.Micros
import dev.adminos.api.domain.ai.eval.AiEvalGate
import dev.adminos.api.domain.ai.eval.AiEvalKey
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.ask.AskPlanTask
import dev.adminos.api.domain.financial.ai.AiCategorizationJob
import dev.adminos.api.domain.financial.ai.AiCategorizationService
import dev.adminos.api.domain.financial.ai.AiCategoryCacheRepository
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.CategorizeMerchantTask
import dev.adminos.api.domain.financial.ai.InMemoryAiCategoryCacheRepository
import dev.adminos.api.domain.financial.ai.PortAiCategoryClient
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.infrastructure.ai.AnthropicProvider
import dev.adminos.api.infrastructure.ai.OllamaProvider
import dev.adminos.api.infrastructure.database.repositories.ExposedAiCallLogRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedAiCategoryCacheRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedAiConsentRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedAiSpendLedger
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.slf4j.LoggerFactory

/** Each feature's task: its prompt version (an eval record must match it, D-68) and its notice (AOS-704). One line per feature. */
internal val AI_TASKS: Map<AiFeature, AiTask<*, *>> = mapOf(
    AiFeature.CATEGORIZATION to CategorizeMerchantTask,
    AiFeature.ASK to AskPlanTask
)

/**
 * The AI layer (AOS-702, D-67). Always: the audit log, the spend ledger, consents (AOS-704), the gateway (with
 * no routes when every feature is off) and the Settings service. Per enabled feature: its route, checked against the committed eval records, so an
 * unevaluated provider/model/prompt refuses to start; then the feature's own beans (AOS-512's categoriser).
 */
internal fun Module.aiDefinitions(useDatabase: Boolean, env: (String) -> String?, evals: AiEvalRecords = AiEvalRecords.load()) {
    val log = LoggerFactory.getLogger("AiLayer")
    val config = AiConfig.from(env)
    val routes = routes(config)
    AiEvalGate.requirePassing(evals, routes.map { AiEvalKey(it.feature, it.provider.kind, it.model, promptVersion(it.feature)) })

    single<AiCallLogRepository> { if (useDatabase) ExposedAiCallLogRepository() else InMemoryAiCallLogRepository() }
    single<AiSpendLedger> { if (useDatabase) ExposedAiSpendLedger() else InMemoryAiSpendLedger() }
    single<AiConsentRepository> { if (useDatabase) ExposedAiConsentRepository() else InMemoryAiConsentRepository() }
    single<AiConsentCheck> { AiConsents(get()) }
    single { AiCostGuard(get(), config.monthlyCapMicros) }
    single { AiGateway(routes, get(), get(), get(), AiCircuitBreaker()) }
    single { AiFeaturesService(get(), AI_TASKS, get(), get(), evals, get(), get()) }

    for (feature in AiFeature.entries) {
        val route = routes.firstOrNull { it.feature == feature }
        if (route == null) log.info("AI {} is off ({}_ENABLED is not true)", feature.key, feature.envPrefix) else
            log.info("AI {} is on: {} {} ({}), monthly cap US$ {}", feature.key, route.provider.kind.key, route.model,
                route.provider.kind.locality.name.lowercase(), Micros.toUsd(config.monthlyCapMicros))
    }
    routes.firstOrNull { it.feature == AiFeature.CATEGORIZATION }?.let { categorization(useDatabase, it) }
}

private fun promptVersion(feature: AiFeature): String =
    AI_TASKS[feature]?.promptVersion ?: error("AI feature ${feature.key} has no task in AI_TASKS")

/** One provider instance per kind in use, shared by the features on it (and by its circuit breaker). */
private fun routes(config: AiConfig): List<AiRoute> {
    val providers = config.features.map { it.provider }.distinct().associateWith { provider(it, config) }
    return config.features.map { s: AiFeatureSetting -> AiRoute.of(s.feature, providers.getValue(s.provider), s.model) }
}

private fun provider(kind: AiProviderKind, config: AiConfig): AiProvider = when (kind) {
    AiProviderKind.ANTHROPIC -> AnthropicProvider.create(config.anthropicApiKey ?: error("validated by AiConfig"))
    AiProviderKind.OLLAMA -> (config.ollama ?: error("validated by AiConfig")).let { OllamaProvider(it.baseUrl, it.timeout) }
}

/** The AI fallback categoriser (AOS-512, D-62): a batch job, never inside ingestion; only business names are sent. */
private fun Module.categorization(useDatabase: Boolean, route: AiRoute) {
    single<AiCategoryCacheRepository> { if (useDatabase) ExposedAiCategoryCacheRepository() else InMemoryAiCategoryCacheRepository() }
    single<AiCategoryClient> { PortAiCategoryClient(get()) }
    single { AiCategorizationService(get(), get(), get(), get(), get(), route.model) }
    single { AiCategorizationJob(get(), get(), get(), get()) } bind ScheduledJob::class
}
