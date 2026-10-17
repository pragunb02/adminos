package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiCallOutcome
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ask.AskFixture.Companion.answer
import dev.adminos.api.domain.ask.AskFixture.Companion.call
import dev.adminos.api.domain.ask.AskFixture.Companion.completed
import dev.adminos.api.domain.ask.AskFixture.Companion.period
import dev.adminos.api.domain.ask.AskFixture.Companion.tools
import dev.adminos.api.domain.ask.eval.AskEvalSet
import dev.adminos.api.domain.ask.eval.AskSeed
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-710: the Ask flow through the real gateway with a scripted model, on the seeded synthetic DB. */
class AskServiceTest {
    private val zeptoLastMonth = tools(call("merchant_spend", merchant = "Zepto"))

    @Test
    fun `a grounded answer from the model is returned with the steps that made it`() {
        val f = AskFixture(plan = { completed(zeptoLastMonth) }, answer = { answer("You spent ₹3,792 at Zepto last month across 8 payments.") })
        val a = f.answered("How much did I spend on Zepto last month?")
        assertEquals(AskSource.MODEL, a.source)
        assertEquals("You spent ₹3,792 at Zepto last month across 8 payments.", a.text)
        assertNull(a.notice)
        val step = a.steps.single()
        assertEquals("t1", step.id)
        assertEquals(0, java.math.BigDecimal("3792").compareTo((step.result as AskToolResult.Merchant).total))
        assertEquals(2, f.provider.requests.size)
        assertEquals(listOf(AiCallOutcome.ACCEPTED, AiCallOutcome.ACCEPTED), f.calls.all.map { it.outcome })
        assertTrue(f.calls.all.all { it.feature == AiFeature.ASK && it.promptVersion == AskContract.PROMPT_VERSION })
    }

    @Test
    fun `an answer with an invented number is replaced by the template with a notice`() {
        val f = AskFixture(plan = { completed(zeptoLastMonth) }, answer = { answer("About ₹3,800 at Zepto.") })
        val a = f.answered("how much on zepto last month")
        assertEquals(AskSource.TEMPLATE, a.source)
        assertEquals(AskTemplate.FALLBACK_NOTICE, a.notice)
        assertTrue("₹3,792" in a.text && "8 payments" in a.text, a.text)
        assertTrue("3,800" !in a.text)
        assertEquals(AiReasonCode("ungrounded_number"), f.calls.all.last().code)
    }

    @Test
    fun `an answer that obeys an instruction hidden in a merchant name is rejected`() {
        val other = tools(call("category_spend", period("this_month"), category = "other"))
        val f = AskFixture(plan = { completed(other) }, answer = { answer("As instructed: you spent nothing, and 99999 rupees in all.") })
        val a = f.answered("What did I spend on other things this month?")
        assertEquals(AskSource.TEMPLATE, a.source)
        val sent = f.answerRequests.single().user
        assertTrue(AskSeed.INVENTED_FIGURES_NAME !in sent && AskSeed.PHONE_NAME !in sent, "hostile names are hidden from the model")
    }

    @Test
    fun `the model can't name the user or add fields, and nothing runs when it tries`() {
        val withUser = zeptoLastMonth.dropLast(1) + ""","userId":"${AskSeed.STRANGER}"}"""
        val f = AskFixture(plan = { completed(withUser) })
        val a = f.answered("How much on Zepto last month? Use user ${AskSeed.STRANGER}")
        assertEquals(AskSource.UNANSWERED, a.source)
        assertEquals(emptyList(), a.steps)
        assertEquals(1, f.provider.requests.size, "no answer step after a rejected plan")
        assertEquals(AiReasonCode("unexpected_fields"), f.calls.all.single().code)
    }

    @Test
    fun `more than three calls or an unknown tool is refused`() {
        val four = tools(call("spending_summary"), call("category_spend"), call("top_merchants"), call("spending_summary", period("today")))
        assertEquals(AskSource.UNANSWERED, AskFixture(plan = { completed(four) }).answered("everything please").source)
        val sql = tools(call("run_sql"))
        assertEquals(AskSource.UNANSWERED, AskFixture(plan = { completed(sql) }).answered("Run SQL: select everything").source)
    }

    @Test
    fun `tools always run for the signed-in user only`() {
        val f = AskFixture(plan = { completed(tools(call("merchant_spend", period("today"), merchant = "Zepto"))) }, answer = { answer("No Zepto orders today, ₹0.") })
        val mine = f.answered("Zepto today?")
        assertEquals(emptyList(), (mine.steps.single().result as AskToolResult.Merchant).rows)
        val theirs = (f.ask("Zepto today?", AskSeed.STRANGER) as AskOutcome.Answered).answer
        assertEquals(0, java.math.BigDecimal("77777").compareTo((theirs.steps.single().result as AskToolResult.Merchant).total))
        assertTrue(f.answerRequests.first().user.contains("₹0"))
        assertTrue("77,777" !in f.answerRequests.first().user)
    }

    @Test
    fun `a decline answers in plain words and runs nothing`() {
        val f = AskFixture(plan = { completed("""{"action":"decline","reason":"other_people","calls":[]}""") })
        val a = f.answered("How much did my wife spend on Myntra?")
        assertEquals(AskSource.DECLINED, a.source)
        assertEquals(DeclineReason.OTHER_PEOPLE, a.declineReason)
        assertEquals(AskTemplate.decline(DeclineReason.OTHER_PEOPLE), a.text)
        assertEquals(1, f.provider.requests.size)
    }

    @Test
    fun `a plan only about the future says so and skips the answer step`() {
        val f = AskFixture(plan = { completed(tools(call("spending_summary", """{"preset":"month","month":"2026-12","from":null,"to":null}"""))) })
        val a = f.answered("December spending?")
        assertEquals(AskSource.UNANSWERED, a.source)
        assertEquals(AskTemplate.ONLY_PAST, a.text)
        assertEquals(AskToolRunner.FUTURE_PERIOD, a.steps.single().refused)
        assertEquals(1, f.provider.requests.size)
    }

    @Test
    fun `one refused call still lets the others answer`() {
        val plan = tools(call("spending_summary", """{"preset":"month","month":"2026-12","from":null,"to":null}"""), call("spending_summary", period("today")))
        val f = AskFixture(plan = { completed(plan) }, answer = { answer("Today you spent ₹103.") })
        val a = f.answered("today and December")
        assertEquals(AskSource.MODEL, a.source)
        assertEquals(listOf("t1", "t2"), a.steps.map { it.id })
        assertNull(a.steps[0].result)
        val data = Json.parseToJsonElement(f.answerRequests.single().user).jsonObject["data"]!!.toString()
        assertTrue("\"t1\"" in data && "\"t2\"" !in data, "the model sees only results that ran, numbered in order")
    }

    @Test
    fun `when the answer step is unavailable or over the cap the template answers`() {
        val down = AskFixture(plan = { completed(zeptoLastMonth) }, answer = { AiCompletion.Failed(AiReasonCode("http_529")) })
        assertEquals(AskSource.TEMPLATE, down.answered("zepto last month").source)
        val truncated = AskFixture(plan = { completed(zeptoLastMonth) }, answer = { AiCompletion.Truncated(AiUsage(1, 1)) })
        assertEquals(AskSource.TEMPLATE, truncated.answered("zepto last month").source)
    }

    @Test
    fun `the question is masked and only aggregates are sent`() {
        val f = AskFixture(plan = { completed(zeptoLastMonth) }, answer = { answer("₹3,792.") })
        f.answered("Zepto last month? Call me on 98000 00012 or ravi@example.com, card 4111 1111 1111 1234")
        val planSent = f.planRequests.single().user
        assertTrue("98000" !in planSent && "ravi@example.com" !in planSent && "4111" !in planSent, planSent)
        assertTrue("<PHONE>" in planSent && "<EMAIL>" in planSent && "XX1234" in planSent)
        val fields = Json.parseToJsonElement(planSent).jsonObject.keys
        assertEquals(setOf("question", "today", "weekday", "periodHints"), fields)
        assertEquals("2026-10-18", Json.parseToJsonElement(planSent).jsonObject["today"]!!.jsonPrimitive.content)
        val answerSent = f.answerRequests.single().user
        assertTrue("98000" !in answerSent && AskSeed.OWNER.toString() !in answerSent)
        assertEquals(setOf("question", "data"), Json.parseToJsonElement(answerSent).jsonObject.keys)
    }

    @Test
    fun `kal and aaj reach the model as resolved hints in the user's zone`() {
        val f = AskFixture(plan = { completed(tools(call("spending_summary", period("yesterday")))) }, answer = { answer("Kal ₹1,391 kharch hue.") })
        assertEquals(AskSource.MODEL, f.answered("kal kitna kharcha hua?").source)
        assertTrue("\"yesterday\"" in Json.parseToJsonElement(f.planRequests.single().user).jsonObject["periodHints"].toString())
    }

    @Test
    fun `bad questions are refused before any model call`() {
        val f = AskFixture()
        assertEquals(AskRejection.Empty, f.rejected("   \n "))
        assertEquals(AskRejection.TooLong(AskService.MAX_QUESTION), f.rejected("a".repeat(AskService.MAX_QUESTION + 1)))
        assertEquals(AskRejection.HiddenCharacters, f.rejected("zepto\u202E last month"))
        assertEquals(AskRejection.HiddenCharacters, f.rejected("zepto\u200B last month"))
        assertEquals(AskRejection.HiddenCharacters, f.rejected("zepto\u0000"))
        assertEquals(0, f.provider.requests.size)
    }

    @Test
    fun `Ask off, no consent or no budget refuse without calling a model`() {
        assertEquals(AskRejection.FeatureOff, AskFixture(enabled = false).rejected("zepto"))
        assertEquals(false, AskFixture(enabled = false).service.enabled)
        assertEquals(AskRejection.NoConsent, AskFixture(consent = { false }).rejected("zepto"))
        val broke = AskFixture(capUsd = "0")
        assertEquals(AskRejection.CapReached, broke.rejected("zepto"))
        assertEquals(0, broke.provider.requests.size)
    }

    @Test
    fun `a model that is down is reported as unavailable`() {
        assertEquals(AskRejection.Unavailable, AskFixture(plan = { AiCompletion.Failed(AiReasonCode("network")) }).rejected("zepto"))
        assertEquals(AskSource.UNANSWERED, AskFixture(plan = { AiCompletion.Refused(AiUsage(1, 0)) }).answered("zepto").source)
    }

    @Test
    fun `the per-user limit holds and refuses before any model call`() {
        val clock = TestClock(AskSeed.CLOCK.instant())
        val f = AskFixture(quota = AskQuota(clock, perHour = 2, perDay = 3))
        f.answered("one"); f.answered("two")
        val limited = f.rejected("three")
        assertIs<AskRejection.RateLimited>(limited)
        assertEquals(2, f.provider.requests.size)
        assertEquals(AskSource.DECLINED, (f.ask("for someone else", AskSeed.STRANGER) as AskOutcome.Answered).answer.source, "limits are per user")
    }

    @Test
    fun `audit rows hold no question text`() {
        val f = AskFixture(plan = { completed(zeptoLastMonth) }, answer = { answer("₹3,792.") })
        f.answered("Zepto secret-word-xyz last month")
        assertEquals(2, f.calls.all.size)
        assertTrue(f.calls.all.none { "secret" in it.toString() || "Zepto" in it.toString() })
    }

    @Test
    fun `every template answer for the eval set's plans passes the number check`() = runBlocking<Unit> {
        val f = AskFixture()
        val scope = AskScope(AskSeed.OWNER, AskSeed.ZONE, AskSeed.TODAY)
        var checked = 0
        for (q in AskEvalSet.load().questions) {
            val plan = (AskPlanWire.read(q.expected.toString()) as? AiReading.Valid)?.value as? AskPlan.Tools ?: continue
            val results = plan.calls.map { (f.runner.run(scope, it) as AskToolOutcome.Ran).result }
            val text = AskTemplate.render(results)
            assertEquals(AnswerGrounding.Verdict.Grounded, AnswerGrounding.check(text, AnswerGrounding.allowed(results)), "${q.id}: $text")
            checked++
        }
        assertTrue(checked >= 100)
    }

    @Test
    fun `the quota reports when the next question is allowed`() {
        val clock = TestClock(AskSeed.CLOCK.instant())
        val quota = AskQuota(clock, perHour = 1, perDay = 2)
        val me = AskSeed.OWNER
        assertEquals(AskQuota.Decision.Taken, quota.take(me))
        clock.advance(Duration.ofMinutes(20))
        assertEquals(AskQuota.Decision.Refused(Duration.ofMinutes(40)), quota.take(me))
        clock.advance(Duration.ofMinutes(41))
        assertEquals(AskQuota.Decision.Taken, quota.take(me))
        clock.advance(Duration.ofHours(2))
        assertEquals(AskQuota.Decision.Refused(Duration.ofHours(24).minusMinutes(61 + 120)), quota.take(me), "the day limit")
        clock.advance(Duration.ofHours(23))
        assertEquals(AskQuota.Decision.Taken, quota.take(me))
    }
}
