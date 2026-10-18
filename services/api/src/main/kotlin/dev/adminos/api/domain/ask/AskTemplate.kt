package dev.adminos.api.domain.ask

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.common.formatInr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Answers written by code, not by a model (D-57, D-67): used when the model's answer fails the number check or
 * can't be had, and for declines. Every figure comes straight from the results, so the text is grounded by
 * construction (a test checks it with [AnswerGrounding]). Merchant names are the user's own and are shown to
 * them as they are; the web renders them as text.
 */
object AskTemplate {
    /** Shown with a templated answer, so the switch from a model's wording is visible (no silent fallback). */
    const val FALLBACK_NOTICE = "I couldn't answer that reliably in my own words, so here are the numbers from your data."

    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun render(results: List<AskToolResult>, comparisons: List<AskComparison> = AskComparison.of(results)): String {
        val lines = results.map(::line).toMutableList()
        for (c in comparisons) {
            val word = if (c.difference.signum() < 0) "less" else "more"
            val pct = c.changePct?.let { " (${it.abs().toPlainString()}% $word)" } ?: ""
            lines += "${period(results[c.later].range).replaceFirstChar { it.uppercase() }}: ${c.difference.abs().formatInr()} $word than ${period(results[c.earlier].range)}$pct."
        }
        return lines.joinToString(" ")
    }

    fun decline(reason: DeclineReason): String = when (reason) {
        DeclineReason.OUT_OF_SCOPE -> "I can only answer questions about your own spending and income in AdminOS, such as \"how much on Zepto last month\"."
        DeclineReason.OTHER_PEOPLE -> "I can only look at your own money, not anyone else's."
        DeclineReason.FUTURE -> "I can only tell you about money already spent, not predict what you'll spend."
        DeclineReason.UNCLEAR -> "I'm not sure what to look up. Try naming a merchant, a category or a period, like \"food this month\"."
    }

    /** When the model's plan couldn't be read, or every call was refused. */
    const val NOT_UNDERSTOOD = "I couldn't work out how to answer that. Try one of the suggestions, or name a merchant, a category or a period."

    /** When every call asked about a period that hasn't started yet. */
    const val ONLY_PAST = "I can only look at money already spent, up to today."

    private fun line(r: AskToolResult): String {
        val p = period(r.range)
        return when (r) {
            is AskToolResult.Summary -> "${cap(p)} you spent ${r.spent.formatInr()} in ${payments(r.payments)} and received ${r.received.formatInr()}."
            is AskToolResult.Categories -> {
                val c = r.call.category
                if (c != null) "${cap(name(c.name))} ${p}: ${r.total.formatInr()} in ${payments(r.rows.sumOf { it.payments })}."
                else if (r.rows.isEmpty()) "${cap(p)} there was no spending."
                else "${cap(p)}: " + r.rows.take(TOP).joinToString(", ") { "${name(it.category.name)} ${it.spent.formatInr()}" } +
                    (if (r.rows.size > TOP) ", and ${r.rows.size - TOP} more" else "") + "; ${r.total.formatInr()} in all."
            }
            is AskToolResult.Merchant ->
                if (r.rows.isEmpty()) "No spending at ${r.call.merchant.name} $p."
                else r.rows.joinToString(" ") { "${it.name} $p: ${it.spent.formatInr()} in ${payments(it.payments)}." }
            is AskToolResult.TopMerchants ->
                if (r.rows.isEmpty()) "${cap(p)} there was no spending${r.call.category?.let { " on ${name(it.name)}" } ?: ""}."
                else "Top merchants $p: " + r.rows.mapIndexed { i, m -> "${i + 1}. ${m.name} ${m.spent.formatInr()}" }.joinToString(", ") + "."
            is AskToolResult.Trend -> {
                val top = r.points.maxByOrNull { it.spent }
                if (top == null || top.spent.signum() <= 0) "${cap(p)} there was no spending."
                else "${cap(p)}: ${r.total.formatInr()} in all; the most in the ${name(r.call.bucket.name)} of ${DAY.format(top.start)} (${top.spent.formatInr()})."
            }
        }
    }

    /** "from 1 Sep 2026 to 30 Sep 2026", or "on 17 Oct 2026" for one day. */
    fun period(range: DateRange): String =
        if (range.from == range.to) "on ${day(range.from)}" else "from ${day(range.from)} to ${day(range.to)}"

    private fun day(d: LocalDate) = DAY.format(d)
    private fun payments(n: Int) = if (n == 1) "1 payment" else "$n payments"
    private fun name(enumName: String) = enumName.lowercase(Locale.ROOT)
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private const val TOP = 5
}
