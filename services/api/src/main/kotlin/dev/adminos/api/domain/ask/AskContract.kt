package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiNotice
import dev.adminos.api.domain.ai.AiPrompt
import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiTask
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.financial.TransactionCategory
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * "Ask your money" (AOS-S7.3, D-67): the vocabulary a model may use. It picks **read-only** tools over the
 * console's own analytics (D-45, D-46) and fills in typed arguments; the server runs them for the signed-in user
 * and the zone from their token. There is no user, account or SQL in the vocabulary, so a model can't ask for
 * anyone else's data or for anything the console doesn't already compute.
 *
 * The eval set (AOS-712) is written in this vocabulary, and the Ask API (AOS-710) reads model plans into it.
 */
object AskContract {
    /** The prompt and schema version of the Ask tasks. A change needs a new eval record (D-68). */
    const val PROMPT_VERSION = "ask-v1"

    /** At most this many tool calls per question (research §7: keep chains short; τ-bench). */
    const val MAX_CALLS = 3

    /** Rows a list tool may return. */
    const val MAX_LIMIT = 10

    /**
     * Exactly what the Ask steps send (D-67), for the Settings notice (AOS-704): the plan step gets the masked
     * question, the user's date and the periods found in it; the answer step gets the masked question and the tool
     * results, which are totals with merchant names only where [dev.adminos.api.domain.ai.SafeName] allows them.
     */
    val NOTICE = AiNotice(
        title = "Ask your money",
        purpose = "Answer a question about your own spending: a model picks read-only console tools to run for you, " +
            "then words a short answer. Every number in an answer must come from those tools, or you get the plain results instead.",
        sends = listOf(
            "Your question, with emails, phone numbers, UPI IDs, ID numbers and account or card numbers masked (a card keeps only its last 4 digits)",
            "Today's date and the periods found in your question",
            "The tools' results: totals per period, category or merchant, and merchant names that are clearly businesses"
        ),
        neverSends = listOf(
            "Single transactions, account or card numbers, UPI IDs",
            "Message, email or statement text",
            "People's names, your name or your user ID"
        )
    )
}

/**
 * Ask's entry in the AI layer's task list until the Ask API (AOS-710) brings its two tasks: the eval gate and
 * Settings read its prompt version and notice, and it never sends anything (fail closed). AOS-710 replaces it.
 */
internal object AskNotBuiltTask : AiTask<Unit, Nothing> {
    val NOT_BUILT = AiReasonCode("not_built")
    override val feature = AiFeature.ASK
    override val promptVersion = AskContract.PROMPT_VERSION
    override val notice = AskContract.NOTICE

    override fun prompt(input: Unit): AiPrompt = AiPrompt.Withhold(NOT_BUILT)

    override fun read(text: String, input: Unit): AiReading<Nothing> = AiReading.Invalid(NOT_BUILT)
}

/** One read-only tool. Each is a thin wrapper over an [dev.adminos.api.domain.analytics.AnalyticsService] method. */
enum class AskTool(val key: String) {
    /** Spent, received and payment count for a period (`/analytics/summary`). */
    SPENDING_SUMMARY("spending_summary"),

    /** Spent per category, or for one category (`/analytics/categories`). */
    CATEGORY_SPEND("category_spend"),

    /** Spent at one merchant the user names (`/analytics/merchants`, matched on the server). */
    MERCHANT_SPEND("merchant_spend"),

    /** The merchants with the most spending, optionally in one category (`/analytics/merchants`). */
    TOP_MERCHANTS("top_merchants"),

    /** Spent per day, week or month (`/analytics/series`). */
    SPENDING_TREND("spending_trend");

    companion object {
        fun fromKey(key: String): AskTool? = entries.firstOrNull { it.key == key }
    }
}

/** Named periods, resolved in the user's zone by [PeriodResolver]. Weeks start on Monday (D-46). */
enum class PeriodPreset(val key: String) {
    TODAY("today"),
    YESTERDAY("yesterday"),
    THIS_WEEK("this_week"),
    LAST_WEEK("last_week"),
    THIS_MONTH("this_month"),
    LAST_MONTH("last_month"),
    LAST_7_DAYS("last_7_days"),
    LAST_30_DAYS("last_30_days"),
    THIS_YEAR("this_year"),

    /** A calendar month named by [PeriodSpec.month] ("August", "Aug 2025"). */
    MONTH("month"),

    /** Explicit dates, [PeriodSpec.from] to [PeriodSpec.to] inclusive. */
    RANGE("range");

    companion object {
        fun fromKey(key: String): PeriodPreset? = entries.firstOrNull { it.key == key }
    }
}

/** A period as the model states it: a preset, plus the month or the dates only when the preset needs them. */
data class PeriodSpec(val preset: PeriodPreset, val month: YearMonth? = null, val from: LocalDate? = null, val to: LocalDate? = null) {
    init {
        when (preset) {
            PeriodPreset.MONTH -> require(month != null && from == null && to == null) { "A month period names only its month" }
            PeriodPreset.RANGE -> require(month == null && from != null && to != null && !to.isBefore(from)) { "A range has from <= to and no month" }
            else -> require(month == null && from == null && to == null) { "A named period takes no month or dates" }
        }
    }

    companion object {
        fun of(preset: PeriodPreset) = PeriodSpec(preset)
        fun month(month: YearMonth) = PeriodSpec(PeriodPreset.MONTH, month = month)
        fun range(from: LocalDate, to: LocalDate) = PeriodSpec(PeriodPreset.RANGE, from = from, to = to)
    }
}

/**
 * A merchant the user named ("Zepto", "swiggy instamart"). It is only ever **matched against the user's own
 * merchants** on the server; it never reaches a query as text. Two queries are the same when their [key] is.
 */
class MerchantQuery private constructor(val name: String) {
    /** Lowercase letters and digits, one space between words: "Swiggy  Instamart!" → "swiggy instamart". */
    val key: String = keyOf(name)

    override fun equals(other: Any?) = other is MerchantQuery && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "MerchantQuery($name)"

    companion object {
        const val MAX_LENGTH = 60

        /** A query for [raw], or null when it is blank, too long or has no letter or digit. */
        fun of(raw: String): MerchantQuery? {
            val name = raw.trim().replace(Regex("\\s+"), " ")
            if (name.isEmpty() || name.length > MAX_LENGTH || keyOf(name).isEmpty()) return null
            return MerchantQuery(name)
        }

        fun keyOf(text: String): String =
            text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
    }
}

/** One tool call with its typed, validated arguments. Each tool takes exactly the arguments it needs. */
sealed interface AskToolCall {
    val tool: AskTool
    val period: PeriodSpec

    data class SpendingSummary(override val period: PeriodSpec) : AskToolCall {
        override val tool get() = AskTool.SPENDING_SUMMARY
    }

    /** All categories when [category] is null. */
    data class CategorySpend(override val period: PeriodSpec, val category: TransactionCategory? = null) : AskToolCall {
        override val tool get() = AskTool.CATEGORY_SPEND
    }

    data class MerchantSpend(override val period: PeriodSpec, val merchant: MerchantQuery) : AskToolCall {
        override val tool get() = AskTool.MERCHANT_SPEND
    }

    data class TopMerchants(override val period: PeriodSpec, val category: TransactionCategory? = null, val limit: Int = DEFAULT_LIMIT) : AskToolCall {
        init {
            require(limit in 1..AskContract.MAX_LIMIT) { "limit is 1..${AskContract.MAX_LIMIT}" }
        }

        override val tool get() = AskTool.TOP_MERCHANTS

        companion object {
            const val DEFAULT_LIMIT = 5
        }
    }

    data class SpendingTrend(override val period: PeriodSpec, val bucket: Bucket, val category: TransactionCategory? = null) : AskToolCall {
        override val tool get() = AskTool.SPENDING_TREND
    }
}

/** Why a question is declined instead of answered. Declining is a correct answer to an unanswerable question. */
enum class DeclineReason(val key: String) {
    /** Not about the user's own spending or income: investments, tax, advice, balances, predictions. */
    OUT_OF_SCOPE("out_of_scope"),

    /** About someone else's money or data. */
    OTHER_PEOPLE("other_people"),

    /** About the future ("how much will I spend"), which no tool knows. */
    FUTURE("future"),

    /** Too vague to pick a tool or a period. */
    UNCLEAR("unclear");

    companion object {
        fun fromKey(key: String): DeclineReason? = entries.firstOrNull { it.key == key }
    }
}

/** What the model decided to do with a question. */
sealed interface AskPlan {
    data class Tools(val calls: List<AskToolCall>) : AskPlan {
        init {
            require(calls.size in 1..AskContract.MAX_CALLS) { "A plan has 1..${AskContract.MAX_CALLS} calls" }
        }
    }

    data class Decline(val reason: DeclineReason) : AskPlan
}
