package dev.adminos.api.domain.ai

import kotlinx.coroutines.runBlocking
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.time.Clock
import java.util.UUID

/**
 * AOS-702, D-67: whatever the input and whatever the model answers, the audit row holds no content.
 * Inputs and answers are random text of 6–40 characters; none may appear in any of the row's strings.
 */
class AiAuditPropertyTest {

    @Provide
    fun texts(): Arbitrary<String> = Arbitraries.strings().withCharRange(' ', '~').withChars('₹', 'é', 'अ').ofMinLength(6).ofMaxLength(40)
        .filter { t -> "secret" !in t && t.trim().any { c -> !(c.isLowerCase() || c.isDigit() || c in "-_.:/") } }

    @Property(tries = 300)
    fun `no input or model output ever reaches the audit row`(@ForAll("texts") input: String, @ForAll("texts") answer: String) {
        val calls = InMemoryAiCallLogRepository()
        val provider = FakeAiProvider { AiCompletion.Completed(answer, AiUsage(12, 3)) }
        val gateway = AiGateway(listOf(AiRoute.of(AiFeature.CATEGORIZATION, provider, FakeAiProvider.MODEL)),
            AiCostGuard(InMemoryAiSpendLedger(), 1_000_000), calls, ALLOW_ALL_CONSENT, AiCircuitBreaker(), Clock.systemUTC())
        runBlocking { gateway.run(EchoTask, UUID.randomUUID(), input) }
        val row = calls.all.single()
        // The row's only strings; ids, counts and times can't hold text. Inputs always have a character no id can.
        val stored = listOf(row.model, row.promptVersion, row.code?.value.orEmpty(), row.feature.key, row.provider.key).joinToString("\u0000")
        check(!stored.contains(input.trim())) { "input leaked into the audit row: $stored" }
        check(!stored.contains(answer.trim())) { "model output leaked into the audit row: $stored" }
    }
}
