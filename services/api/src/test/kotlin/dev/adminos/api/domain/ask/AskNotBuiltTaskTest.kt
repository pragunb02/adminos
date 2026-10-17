package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiPrompt
import dev.adminos.api.domain.ai.AiReading
import kotlin.test.Test
import kotlin.test.assertEquals

/** Until the Ask API (AOS-710) brings its tasks, Ask is listed with its notice but never sends anything. */
class AskNotBuiltTaskTest {

    @Test
    fun `it carries the Ask prompt version and notice for the eval gate and Settings`() {
        assertEquals(AiFeature.ASK, AskNotBuiltTask.feature)
        assertEquals(AskContract.PROMPT_VERSION, AskNotBuiltTask.promptVersion)
        assertEquals(AskContract.NOTICE, AskNotBuiltTask.notice)
    }

    @Test
    fun `it withholds every input and accepts no reply`() {
        assertEquals(AiPrompt.Withhold(AskNotBuiltTask.NOT_BUILT), AskNotBuiltTask.prompt(Unit))
        assertEquals(AiReading.Invalid(AskNotBuiltTask.NOT_BUILT), AskNotBuiltTask.read("""{"action":"tools"}""", Unit))
    }
}
