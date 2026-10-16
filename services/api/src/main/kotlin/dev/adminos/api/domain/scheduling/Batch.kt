package dev.adminos.api.domain.scheduling

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger

/** How a batch went: items done, and items whose work threw (logged, then skipped). */
data class BatchCounts(val succeeded: Int, val failed: Int)

/**
 * Runs [work] for every item, so that one bad item (a user with broken data, a bill that fails to
 * save) is logged and counted but never stops the rest of a scheduled run.
 * [describe] names the item in logs: an ID, never personal data.
 */
suspend fun <T> forEachIsolated(items: List<T>, logger: Logger, describe: (T) -> String, work: suspend (T) -> Unit): BatchCounts {
    var failed = 0
    for (item in items) {
        try {
            work(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed++
            logger.error("Skipped {} after an error", describe(item), e)
        }
    }
    return BatchCounts(succeeded = items.size - failed, failed = failed)
}
