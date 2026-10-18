package dev.adminos.api.domain.financial.splits.eval

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal

/**
 * The floor the matcher must not fall below (AOS-911, as D-61 does for categorisation). Raising it is a deliberate
 * commit: `./gradlew -q splitwiseReport -PwriteBaseline`.
 */
@Serializable
data class SplitBaseline(
    val seed: Int,
    val autoPrecision: Double,
    val autoRecall: Double,
    val reviewRecall: Double,
    val appliedWrong: Int,
    val falseMatches: Int,
    val friendPaidSharesCorrect: Int,
    val errorAfterImport: String,
    val errorAfterReview: String
) {
    /** @return what got worse, empty when nothing did */
    fun regressions(r: SplitEvalReport): List<String> = buildList {
        if (r.autoPrecision < autoPrecision) add("auto precision ${r.autoPrecision} < $autoPrecision")
        if (r.autoRecall < autoRecall) add("auto recall ${r.autoRecall} < $autoRecall")
        if (r.reviewRecall < reviewRecall) add("review recall ${r.reviewRecall} < $reviewRecall")
        if (r.appliedWrong > appliedWrong) add("wrong matches applied ${r.appliedWrong} > $appliedWrong")
        if (r.falseMatches > falseMatches) add("matches with no bank record ${r.falseMatches} > $falseMatches")
        if (r.friendPaidSharesCorrect < friendPaidSharesCorrect) add("friend-paid shares right ${r.friendPaidSharesCorrect} < $friendPaidSharesCorrect")
        if (BigDecimal(r.errorAfterImport) > BigDecimal(errorAfterImport)) add("spend error after import ${r.errorAfterImport} > $errorAfterImport")
        if (BigDecimal(r.errorAfterReview) > BigDecimal(errorAfterReview)) add("spend error after review ${r.errorAfterReview} > $errorAfterReview")
    }

    companion object {
        fun of(r: SplitEvalReport) = SplitBaseline(r.seed, r.autoPrecision, r.autoRecall, r.reviewRecall, r.appliedWrong, r.falseMatches,
            r.friendPaidSharesCorrect, r.errorAfterImport, r.errorAfterReview)
    }
}

internal val evalJson = Json { prettyPrint = true; encodeDefaults = true }

/** `./gradlew -q splitwiseReport [-PwriteBaseline]`: prints the report, writes it as JSON, optionally records the floor. */
fun main(args: Array<String>) = runBlocking {
    val report = SplitEvaluator.run()
    val text = evalJson.encodeToString(SplitEvalReport.serializer(), report)
    println(text)
    args.getOrNull(0)?.let { File(it).apply { parentFile.mkdirs() }.writeText(text + "\n") }
    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val out = File(args[i + 1], "baseline.json")
        out.writeText(evalJson.encodeToString(SplitBaseline.serializer(), SplitBaseline.of(report)) + "\n")
        println("Baseline written to $out")
    }
}
