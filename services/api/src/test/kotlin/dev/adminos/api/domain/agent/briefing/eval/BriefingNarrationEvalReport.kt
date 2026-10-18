package dev.adminos.api.domain.agent.briefing.eval

import dev.adminos.api.domain.ai.eval.AiEvalRecords
import java.io.File
import java.time.LocalDate

/**
 * `./gradlew -q briefingNarrationReport` (AOS-716): prints the narration eval's scores and every miss.
 * `-PwriteEvalRecord` records them in `src/main/resources/ai/evals.json`, the start-up gate (D-68).
 */
fun main(args: Array<String>) {
    val report = BriefingNarrationEval.run()
    println("Briefing narration eval (${report.outcomes.size} cases, recorded-synthetic answers)")
    for ((k, v) in report.metrics.toSortedMap()) println("  %-24s %.4f (floor %.2f)".format(k, v, BriefingNarrationEval.FLOOR.getValue(k)))
    if (report.misses.isNotEmpty()) println("Misses:")
    for (m in report.misses) println("  ${m.case.id} (${m.case.kind}): ${if (m.kept) "kept" else "rejected as ${m.code}"}")
    val i = args.indexOf("--write-eval-records")
    if (i >= 0) {
        val file = File(args[i + 1])
        val records = AiEvalRecords.parse(file.readText()).with(BriefingNarrationEval.record(report, LocalDate.now().toString()))
        file.writeText(records.toJson())
        println("Wrote ${BriefingNarrationEval.KEY} to $file")
    }
}
