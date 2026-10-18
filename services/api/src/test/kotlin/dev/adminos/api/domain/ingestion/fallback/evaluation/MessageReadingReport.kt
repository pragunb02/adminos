package dev.adminos.api.domain.ingestion.fallback.evaluation

import dev.adminos.api.domain.ai.eval.AiEvalRecords
import java.io.File

/**
 * The message-reading eval (AOS-705): `./gradlew -q messageReadingReport` prints the scores of the synthetic golden
 * set through the shipped path; `-PwriteBaseline` also writes the records the start-up eval gate reads
 * (`src/main/resources/ai/evals.json`). Args: `[--eval-records <evals.json>]`.
 */
fun main(args: Array<String>) {
    val graded = MessageEvaluation.run()
    val metrics = MessageEvalRecord.metrics(graded)
    println("Message reading on ${graded.size} synthetic messages (recorded-synthetic answers):")
    metrics.forEach { (k, v) -> println("  %-22s %.4f".format(k, v)) }
    Outcome.entries.forEach { o -> println("  outcome %-14s %d".format(o.name.lowercase(), graded.count { it.outcome == o })) }
    graded.filter { it.outcome == Outcome.REJECTED }.groupingBy { it.code ?: "?" }.eachCount().toSortedMap()
        .forEach { (code, n) -> println("  rejected %-22s %d".format(code, n)) }

    val at = args.indexOf("--eval-records")
    if (at >= 0) {
        val file = File(args.getOrNull(at + 1) ?: error("--eval-records needs a path"))
        var records = AiEvalRecords.parse(file.readText())
        for (key in MessageEvalRecord.KEYS) records = records.with(MessageEvalRecord.record(key, graded))
        file.writeText(records.toJson())
        println("Eval records for messages written in ${file.path} (commit them with the change that moved the numbers)")
    }
}
