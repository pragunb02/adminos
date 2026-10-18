package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ask.AskFixture.Companion.answer
import dev.adminos.api.domain.ask.AskFixture.Companion.call
import dev.adminos.api.domain.ask.AskFixture.Companion.completed
import dev.adminos.api.domain.ask.AskFixture.Companion.tools
import dev.adminos.api.domain.ask.eval.AskSeed
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-711: thumbs up or down on a recent answer stores the masked question and the plan, for that user only. */
class AskFeedbackTest {
    private fun askId(o: AskOutcome) = (o as AskOutcome.Answered).askId

    @Test
    fun `a vote stores the masked question and the plan, and a second vote replaces it`() = runBlocking<Unit> {
        val f = AskFixture(plan = { completed(tools(call("merchant_spend", merchant = "Zepto"))) }, answer = { answer("₹3,792.") })
        val id = askId(f.ask("Zepto last month? my number is 98000 00012"))
        assertEquals(AskFeedbackService.Result.Saved, f.feedback.vote(AskSeed.OWNER, id, helpful = true))
        assertEquals(AskFeedbackService.Result.Saved, f.feedback.vote(AskSeed.OWNER, id, helpful = false))
        val saved = f.feedbackRepository.forUser(AskSeed.OWNER).single()
        assertEquals(false, saved.helpful)
        assertEquals("Zepto last month? my number is <PHONE>", saved.question)
        assertTrue(saved.planJson!!.contains("\"merchant_spend\""))
        assertEquals(AskSource.MODEL, saved.source)
        assertEquals(AskContract.PROMPT_VERSION, saved.promptVersion)
        assertTrue("98000" !in saved.toString() && "Zepto" !in saved.toString())
    }

    @Test
    fun `someone else's or an unknown answer is not found`() = runBlocking<Unit> {
        val f = AskFixture()
        val id = askId(f.ask("hmm"))
        assertEquals(AskFeedbackService.Result.NotFound, f.feedback.vote(AskSeed.STRANGER, id, true))
        assertEquals(AskFeedbackService.Result.NotFound, f.feedback.vote(AskSeed.OWNER, UUID.randomUUID(), true))
        assertEquals(emptyList(), f.feedbackRepository.forUser(AskSeed.STRANGER))
        assertEquals(AskFeedbackService.Result.Saved, f.feedback.vote(AskSeed.OWNER, id, true))
    }

    @Test
    fun `an unreadable plan is stored as no plan`() = runBlocking<Unit> {
        val f = AskFixture(plan = { completed("""{"action":"tools"}""") })
        val id = askId(f.ask("??"))
        f.feedback.vote(AskSeed.OWNER, id, false)
        val saved = f.feedbackRepository.forUser(AskSeed.OWNER).single()
        assertNull(saved.planJson)
        assertEquals(AskSource.UNANSWERED, saved.source)
    }

    @Test
    fun `recent answers expire after an hour and keep at most twenty per user`() {
        val clock = TestClock(AskSeed.CLOCK.instant())
        val recent = RecentAsks(clock)
        val first = recent.remember(AskSeed.OWNER, "q0", null, AskSource.DECLINED)
        assertNotNull(recent.find(AskSeed.OWNER, first))
        clock.advance(Duration.ofMinutes(61))
        assertNull(recent.find(AskSeed.OWNER, first))
        val ids = (1..25).map { recent.remember(AskSeed.OWNER, "q$it", null, AskSource.DECLINED) }
        assertTrue(ids.take(5).all { recent.find(AskSeed.OWNER, it) == null }, "the oldest five are gone")
        assertTrue(ids.drop(5).all { recent.find(AskSeed.OWNER, it) != null })
        val theirs = recent.remember(AskSeed.STRANGER, "theirs", null, AskSource.MODEL)
        assertNotNull(recent.find(AskSeed.STRANGER, theirs), "another user's answers don't push the owner's out")
        assertTrue(ids.drop(5).all { recent.find(AskSeed.OWNER, it) != null })
        assertTrue("theirs" !in recent.find(AskSeed.STRANGER, theirs).toString())
    }

    @Test
    fun `feedback rows are bounded`() {
        assertFailsWith<IllegalArgumentException> {
            AskFeedback(UUID.randomUUID(), AskSeed.OWNER, true, "x".repeat(AskService.MAX_QUESTION + 1), null, AskSource.MODEL, "ask-v1", AskSeed.CLOCK.instant())
        }
        assertFailsWith<IllegalArgumentException> {
            AskFeedback(UUID.randomUUID(), AskSeed.OWNER, true, "q", "x".repeat(AskPlanWire.MAX_TEXT + 1), AskSource.MODEL, "ask-v1", AskSeed.CLOCK.instant())
        }
    }
}
