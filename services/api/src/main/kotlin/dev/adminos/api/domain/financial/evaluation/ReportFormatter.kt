package dev.adminos.api.domain.financial.evaluation

import java.util.Locale

/** Renders an [EvaluationReport] as a plain-text table for the terminal (AOS-511). */
object ReportFormatter {

    fun table(title: String, report: EvaluationReport, maxMisses: Int = 50): String = buildString {
        appendLine("== $title ==")
        appendLine("rows ${report.total}  correct ${report.correct}  accuracy ${pct(report.accuracy)}  " +
            "coverage (not Other) ${pct(report.coverage)}  macro-F1 ${num(report.macroF1)}")
        appendLine("subcategory: ${report.subcategoryCorrect}/${report.subcategoryScored} = ${pct(report.subcategoryAccuracy)}")
        appendLine("by source: " + report.bySource.entries.joinToString("  ") { "${it.key} ${it.value}" })
        appendLine()
        appendLine(row("category", "support", "predicted", "precision", "recall", "F1"))
        for (s in report.perCategory) {
            appendLine(row(s.category.name, s.support.toString(), s.predicted.toString(), num(s.precision), num(s.recall), num(s.f1)))
        }
        if (report.confusions.isNotEmpty()) {
            appendLine()
            appendLine("confusions (expected → predicted):")
            for (c in report.confusions) appendLine("  ${c.expected} → ${c.predicted}: ${c.count}  [${c.ids.joinToString(" ")}]")
        }
        if (report.byTag.isNotEmpty()) {
            appendLine()
            appendLine("hard cases by tag:")
            for (t in report.byTag) appendLine("  ${t.tag.padEnd(14)} ${t.correct}/${t.total} = ${pct(t.accuracy)}")
        }
        if (report.misses.isNotEmpty()) {
            appendLine()
            appendLine("misses (${report.misses.size}):")
            for (m in report.misses.take(maxMisses)) {
                appendLine("  ${m.id.padEnd(6)} expected ${show(m.expected).padEnd(30)} got ${show(m.predicted).padEnd(30)} " +
                    "(${m.source})  ${m.merchantRaw ?: m.upiVpa.orEmpty()}")
            }
            if (report.misses.size > maxMisses) appendLine("  … ${report.misses.size - maxMisses} more in the JSON report")
        }
    }

    private fun row(vararg cells: String) =
        cells[0].padEnd(14) + cells.drop(1).joinToString("") { it.padStart(11) }

    private fun show(l: Label) = l.category.name + (l.subcategory?.let { "/$it" } ?: "")
    private fun pct(x: Double) = String.format(Locale.ROOT, "%.1f%%", x * 100)
    private fun num(x: Double) = String.format(Locale.ROOT, "%.3f", x)
}
