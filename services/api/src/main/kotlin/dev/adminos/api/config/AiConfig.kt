package dev.adminos.api.config

import dev.adminos.api.domain.ai.AiCallRecord
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.Micros
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

/** One enabled AI feature: which provider and model serve it. */
data class AiFeatureSetting(val feature: AiFeature, val provider: AiProviderKind, val model: String)

/** Where the local Ollama server is, and how long a local answer may take. */
data class OllamaSettings(val baseUrl: URI, val timeout: Duration)

/**
 * The AI layer's settings (AOS-702, D-67), read once at start-up. Every feature is **off** unless
 * `AI_<FEATURE>_ENABLED=true`; then:
 * - `AI_<FEATURE>_PROVIDER` is `anthropic` or `ollama`; unset, it is the feature's [AiFeature.defaultProvider]
 *   (`anthropic` for categorization and ask; `ollama` for messages, which reads raw text, D-67);
 * - `AI_<FEATURE>_MODEL` names the model (on Anthropic it defaults to the feature's [AiFeature.defaultAnthropicModel]; on Ollama it is required);
 * - Anthropic needs `ANTHROPIC_API_KEY`; Ollama needs `OLLAMA_BASE_URL` (and takes `OLLAMA_TIMEOUT_SECONDS`, default 120).
 * `AI_MONTHLY_CAP_USD` (default 2.00) caps this server's AI spend per UTC month; 0 allows only free (local) calls.
 * A half-configured or malformed setup refuses to start with a message naming the setting. The key comes
 * only from the environment and is never printed.
 */
class AiConfig(
    val features: List<AiFeatureSetting>,
    val anthropicApiKey: String?,
    val ollama: OllamaSettings?,
    val monthlyCapMicros: Long
) {
    init {
        require(features.map { it.feature }.toSet().size == features.size) { "One setting per feature" }
        require(monthlyCapMicros in 0..MAX_CAP_MICROS) { "AI_MONTHLY_CAP_USD is 0..${Micros.toUsd(MAX_CAP_MICROS)}" }
        require(features.none { it.provider == AiProviderKind.ANTHROPIC } || !anthropicApiKey.isNullOrBlank()) { "Anthropic features need ANTHROPIC_API_KEY" }
        require(features.none { it.provider == AiProviderKind.OLLAMA } || ollama != null) { "Ollama features need OLLAMA_BASE_URL" }
    }

    fun setting(feature: AiFeature): AiFeatureSetting? = features.firstOrNull { it.feature == feature }

    /** Never print the key. */
    override fun toString() = "AiConfig(features=$features, anthropicApiKey=${if (anthropicApiKey == null) "unset" else "***"}, " +
        "ollama=$ollama, monthlyCap=US$${Micros.toUsd(monthlyCapMicros)})"

    companion object {
        /** Categorisation's default model (kept for the AOS-511 report); each feature names its own. */
        val DEFAULT_ANTHROPIC_MODEL: String = AiFeature.CATEGORIZATION.defaultAnthropicModel
        const val DEFAULT_CAP_USD = "2.00"
        private val MAX_CAP_MICROS = Micros.ofUsd("10000")
        private const val DEFAULT_OLLAMA_TIMEOUT_SECONDS = 120L

        fun from(getenv: (String) -> String?): AiConfig {
            fun value(key: String) = getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
            val features = AiFeature.entries.mapNotNull { feature(it, ::value) }
            val anthropic = features.any { it.provider == AiProviderKind.ANTHROPIC }
            val key = if (anthropic) {
                value("ANTHROPIC_API_KEY") ?: throw IllegalArgumentException(
                    "${features.first { it.provider == AiProviderKind.ANTHROPIC }.feature.envPrefix}_ENABLED=true needs ANTHROPIC_API_KEY (or set it to false)")
            } else null
            val ollama = if (features.any { it.provider == AiProviderKind.OLLAMA }) ollama(::value) else null
            val cap = value("AI_MONTHLY_CAP_USD") ?: DEFAULT_CAP_USD
            val capMicros = try {
                Micros.ofUsd(cap)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("AI_MONTHLY_CAP_USD has more than 6 decimal places: '$cap'", e)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("AI_MONTHLY_CAP_USD must be an amount in US dollars, e.g. 2.00, was '$cap'", e)
            }
            require(capMicros <= MAX_CAP_MICROS) { "AI_MONTHLY_CAP_USD is at most ${Micros.toUsd(MAX_CAP_MICROS)}" }
            return AiConfig(features, key, ollama, capMicros)
        }

        /** The feature's setting when enabled, null when off; throws when its settings are malformed. */
        private fun feature(feature: AiFeature, value: (String) -> String?): AiFeatureSetting? {
            val p = feature.envPrefix
            val enabled = when (val flag = value("${p}_ENABLED")?.lowercase()) {
                null, "false" -> false
                "true" -> true
                else -> throw IllegalArgumentException("${p}_ENABLED must be true or false, was '$flag'")
            }
            if (!enabled) return null
            val providerKey = value("${p}_PROVIDER")?.lowercase() ?: feature.defaultProvider.key
            val provider = AiProviderKind.fromKey(providerKey)
                ?: throw IllegalArgumentException("${p}_PROVIDER must be one of ${AiProviderKind.entries.joinToString { it.key }}, was '$providerKey'")
            val model = value("${p}_MODEL") ?: when (provider) {
                AiProviderKind.ANTHROPIC -> feature.defaultAnthropicModel
                AiProviderKind.OLLAMA -> throw IllegalArgumentException("${p}_PROVIDER=ollama needs ${p}_MODEL (e.g. a model from the AOS-703 bake-off)")
            }
            require(model.matches(AiCallRecord.ID)) { "${p}_MODEL is not a model id" }
            return AiFeatureSetting(feature, provider, model)
        }

        private fun ollama(value: (String) -> String?): OllamaSettings {
            val raw = value("OLLAMA_BASE_URL") ?: throw IllegalArgumentException("An AI feature uses ollama: set OLLAMA_BASE_URL (e.g. http://ollama:11434 in compose)")
            val url = try {
                URI(raw.trimEnd('/'))
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("OLLAMA_BASE_URL is not a URL", e)
            }
            require(url.scheme in setOf("http", "https") && !url.host.isNullOrBlank()) { "OLLAMA_BASE_URL must be an http(s) URL with a host" }
            require(url.rawUserInfo == null) { "OLLAMA_BASE_URL must not contain credentials" }
            // Prompts carry financial text: plain http only to this machine or a compose service (no dot in the
            // name, so it never leaves the compose network); anything else must be https (D-91).
            require(url.scheme == "https" || isPrivateHost(url.host)) {
                "OLLAMA_BASE_URL must use https unless it is localhost, a loopback IP or the compose service ollama"
            }
            val seconds = value("OLLAMA_TIMEOUT_SECONDS")?.let {
                it.toLongOrNull()?.takeIf { s -> s in 1..600 } ?: throw IllegalArgumentException("OLLAMA_TIMEOUT_SECONDS is 1..600, was '$it'")
            } ?: DEFAULT_OLLAMA_TIMEOUT_SECONDS
            return OllamaSettings(url, Duration.ofSeconds(seconds))
        }

        /** Where plain http may go: `localhost`, a literal loopback IP, or the compose service [COMPOSE_OLLAMA]. Nothing else. */
        internal fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().removePrefix("[").removeSuffix("]")
            return h == "localhost" || h == COMPOSE_OLLAMA || h == "::1" || h == "0:0:0:0:0:0:0:1" || LOOPBACK_V4.matches(h)
        }

        /** The service name in docker-compose.yml's `ollama` profile (AOS-703). */
        private const val COMPOSE_OLLAMA = "ollama"
        /** A literal 127.x.y.z address (each octet 0–255), not a DNS name that merely starts with "127.". */
        private val LOOPBACK_V4 = Regex("^127\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
    }
}
