package dev.adminos.api.domain.forecast.evaluation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The checked-in floor for the forecast (AOS-714, in the D-61 style): the shipped engine may not score worse
 * on the synthetic backtest. Raised deliberately with `./gradlew -q forecastReport -PwriteBaseline`.
 */
@Serializable
data class ForecastBaseline(
    val method: String,
    val histories: Int,
    val forecasts: Int,
    val mape: Double,
    val relMae: Double,
    val coverage: Double,
    val salaryDateAccuracy: Double,
    val irregularHonesty: Double
) {
    companion object {
        const val RESOURCE = "/forecast/baseline.json"
        val json = Json { prettyPrint = true; encodeDefaults = true }

        fun of(report: BacktestReport): ForecastBaseline {
            val engine = report.engine()
            return ForecastBaseline(engine.method, report.histories, engine.forecasts, engine.mape, engine.relMae,
                engine.coverage ?: 0.0, report.salary.dateAccuracy, report.salary.irregularHonesty)
        }

        fun load(resource: String = RESOURCE): ForecastBaseline {
            val text = ForecastBaseline::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("baseline not found: $resource")
            return json.decodeFromString(serializer(), text)
        }
    }
}

fun BacktestReport.engine(): MethodScore = scores.first { it.method == "engine (shipped)" }

/** A plain-text table of every method's scores. */
fun BacktestReport.table(): String = buildString {
    appendLine("Forecast backtest (AOS-714): $histories synthetic histories, forecasts on the 10th and 20th of each month")
    appendLine(String.format("%-36s %6s %8s %10s %7s %9s %7s %8s %8s", "method", "n", "MAPE%", "MAE ₹", "relMAE", "coverage", "width", "MAPE@10", "MAPE@20"))
    for (s in scores) {
        appendLine(String.format("%-36s %6d %8.2f %10.0f %7.3f %9s %7s %8.2f %8.2f", s.method, s.forecasts, s.mape, s.mae, s.relMae,
            s.coverage?.let { String.format("%.3f", it) } ?: "—", s.width?.let { String.format("%.3f", it) } ?: "—", s.mapeDay10, s.mapeDay20))
    }
    appendLine("Ranges on the shipped run-rate:")
    for (s in intervals) appendLine(String.format("  %-34s coverage %s, width %s, n %d", s.method, s.coverage?.toString() ?: "—", s.width?.toString() ?: "—", s.forecasts))
    appendLine("MAPE% by persona (engine vs seasonal naive): " + byPersona.entries.joinToString { (k, v) -> "$k ${v[0]} vs ${v[1]}" })
    appendLine("Salary: next date exactly right ${salary.dateAccuracy} (within 2 days ${salary.dateWithin2Days}); irregular income left unknown ${salary.irregularHonesty}; " +
        "money-left error ${salary.leftErrorShare} of a salary (${salary.forecasts} forecasts)")
    append("Forecasts refused for too little history: $notEnoughHistory")
}

/**
 * `./gradlew -q forecastReport`: prints the backtest table and writes the JSON report. Args: `<report.json>`,
 * then optionally `--write-baseline <dir>` (with `-PwriteBaseline`).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <report.json> [--write-baseline <dir>]" }
    val report = ForecastBacktest.run()
    println(report.table())
    val late = LateBillsBacktest.run()
    println("Late bills (AOS-721, the electricity bill known ${LateBillsBacktest.LEAD_DAYS} days ahead): month-end MAPE ${late.mapeWithout}% without projected bills, " +
        "${late.mapeWith}% with (on the 10th ${late.mapeDay10Without}% vs ${late.mapeDay10With}%), ${late.forecasts} forecasts")
    val out = File(args[0]).also { it.parentFile?.mkdirs() }
    out.writeText(ForecastBaseline.json.encodeToString(BacktestReport.serializer(), report))
    println("JSON report: ${out.path}")
    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val dir = File(args.getOrNull(i + 1) ?: error("--write-baseline needs a directory"))
        File(dir, "baseline.json").writeText(ForecastBaseline.json.encodeToString(ForecastBaseline.serializer(), ForecastBaseline.of(report)) + "\n")
        println("Baseline written in ${dir.path} (commit it with the change that moved the numbers)")
    }
}
