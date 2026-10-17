package dev.adminos.api.domain.financial.evaluation

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalGate
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.ai.eval.AiEvalVerdict
import dev.adminos.api.infrastructure.ai.OllamaProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AOS-703: the bake-off against a local stub of Ollama's `/api/chat` (Ollama itself isn't needed). The stub
 * plays three "models": one that gives the recorded Claude answers, one that always says Other, and one that
 * answers nonsense.
 */
class CategorizationBakeOffTest {

    private val recorded = RecordedAiClient.load()
    private val seenMerchants = CopyOnWriteArrayList<String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/chat") { ex ->
            val body = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            val model = body.getValue("model").jsonPrimitive.content
            val user = body.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content
            val merchant = Json.parseToJsonElement(user).jsonObject.getValue("merchant").jsonPrimitive.content
            seenMerchants += merchant
            val content = when (model) {
                "claude-like:1b" -> recorded.answerFor(merchant)?.let { """{"category":"${it.category}","confidence":${it.confidence},"reason":"${it.reason}"}""" }
                    ?: """{"category":"OTHER","confidence":0.1,"reason":"unknown"}"""
                "shrug:1b" -> """{"category":"OTHER","confidence":0.2,"reason":"not sure"}"""
                else -> "I think it is food"
            }
            val reply = JsonObject(mapOf("message" to JsonObject(mapOf("content" to JsonPrimitive(content))), "done_reason" to JsonPrimitive("stop"),
                "prompt_eval_count" to JsonPrimitive(90), "eval_count" to JsonPrimitive(20))).toString().toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val provider = OllamaProvider(URI("http://127.0.0.1:${server.address.port}"), Duration.ofSeconds(5))
    private val bakeOff = CategorizationBakeOff()

    @Test
    fun `a model that gives Claude's answers scores like rules plus Claude and agrees with it fully`() = runBlocking<Unit> {
        val run = bakeOff.run(provider, "claude-like:1b")
        val reference = bakeOff.baselines()[1]
        assertEquals(reference.report.accuracy, run.report.accuracy)
        assertEquals(reference.report.macroF1, run.report.macroF1)
        assertEquals(1.0, run.agreementWithClaude)
        assertEquals(1.0, run.ruleDecidedUnchanged)
        assertTrue(run.passes, "beats the rules baseline: ${run.drops}")
        assertEquals(run.asked, run.answered + run.rejected + run.unavailable)
        assertTrue(run.meanLatencyMs != null && run.p95LatencyMs != null)
    }

    @Test
    fun `only golden-set business names are sent, never a person's payment`() = runBlocking<Unit> {
        bakeOff.run(provider, "claude-like:1b")
        assertTrue(seenMerchants.isNotEmpty())
        // AOS-512's recorded merchants are exactly the business names its privacy gate lets through; AiEvaluationTest
        // proves no payment to a person is among them.
        assertEquals(emptySet(), seenMerchants.toSet() - recorded.merchants, "the bake-off asks the same merchants AOS-512 recorded")
    }

    @Test
    fun `a model that always says Other matches the rules and passes, adding no labels`() = runBlocking<Unit> {
        val run = bakeOff.run(provider, "shrug:1b")
        val rules = bakeOff.baselines()[0]
        assertEquals(rules.report.accuracy, run.report.accuracy)
        assertEquals(0, run.report.bySource["ai"] ?: 0)
        assertTrue(run.passes)
    }

    @Test
    fun `a model that answers nonsense is rejected every time and changes nothing`() = runBlocking<Unit> {
        val run = bakeOff.run(provider, "babble:1b")
        assertEquals(run.asked, run.rejected)
        assertEquals(0, run.answered)
        assertNull(run.agreementWithClaude)
        assertEquals(1.0, run.ruleDecidedUnchanged)
        assertFalse(run.passes, "matching the rules isn't enough when no answer is valid")
        assertEquals(listOf(CategorizationBakeOff.VALID_ANSWER_RATE), run.drops.map { it.metric })
    }

    @Test
    fun `a stopped server makes every question unavailable`() = runBlocking<Unit> {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val run = bakeOff.run(OllamaProvider(URI("http://127.0.0.1:$closed"), Duration.ofSeconds(2)), "any:1b")
        assertEquals(run.asked, run.unavailable)
    }

    @Test
    fun `the table names every labeller and the gate, and lists failures`() = runBlocking<Unit> {
        val runs = bakeOff.baselines() + bakeOff.run(provider, "claude-like:1b")
        val failing = runs.last().copy(name = "rules + ollama weak:1b", drops = listOf(dev.adminos.api.domain.ai.eval.MetricDrop("accuracy", 0.826, 0.7)))
        val table = CategorizationBakeOff.table(runs + failing)
        for (name in listOf("rules only", "rules + Claude (recorded)", "rules + ollama claude-like:1b", "agrees w/ Claude", "pass", "FAIL")) assertContains(table, name)
        assertContains(table, "accuracy is 0.7, below its floor 0.826")
    }

    @Test
    fun `a passing run becomes a live eval record that the gate accepts`() = runBlocking<Unit> {
        val run = bakeOff.run(provider, "claude-like:1b")
        val record = bakeOff.record(CategorizationBakeOff.key("claude-like:1b"), run, "2026-10-17")
        assertEquals(AiEvalAnswers.LIVE, record.answers)
        assertEquals("ollama", record.provider)
        assertIs<AiEvalVerdict.Passed>(AiEvalGate.verdict(AiEvalRecords(listOf(record)), record.key))
    }

    @Test
    fun `the recording lists each answer without commas in the reason`() = runBlocking<Unit> {
        val run = bakeOff.run(provider, "claude-like:1b")
        val text = recording("claude-like:1b", run)
        val rows = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
        assertEquals(run.answered, rows.size)
        assertTrue(rows.all { it.split(',').size == 4 })
        assertEquals(run.answers.keys, RecordedAiClient.parse(text).merchants)
    }

    @Test
    fun `the command line needs a model and reads Ollama's address from the environment`() {
        assertFailsWith<IllegalArgumentException> { BakeOffArgs.parse(arrayOf("--models", " "), { null }) }
        assertFailsWith<IllegalArgumentException> { BakeOffArgs.parse(arrayOf("--models"), { null }) }
        val defaults = BakeOffArgs.parse(arrayOf("--models", "qwen3:4b, gemma3:4b"), { null })
        assertEquals(listOf("qwen3:4b", "gemma3:4b"), defaults.models)
        assertEquals(URI(BakeOffArgs.DEFAULT_BASE_URL), defaults.baseUrl)
        assertNull(defaults.evalRecords)
        val env = mapOf("OLLAMA_BASE_URL" to "http://ollama:11434", "OLLAMA_TIMEOUT_SECONDS" to "30")
        val set = BakeOffArgs.parse(arrayOf("--models", "x:1b", "--out", "/tmp/x", "--write-eval-records", "evals.json"), env::get)
        assertEquals(URI("http://ollama:11434"), set.baseUrl)
        assertEquals(URI("http://127.0.0.1:1"), BakeOffArgs.parse(arrayOf("--models", "x:1b", "--base-url", "http://127.0.0.1:1"), env::get).baseUrl)
        assertEquals(Duration.ofSeconds(30), set.timeout)
        assertEquals(File("evals.json"), set.evalRecords)
        assertFailsWith<IllegalArgumentException> { BakeOffArgs.parse(arrayOf("--models", "x"), mapOf("OLLAMA_TIMEOUT_SECONDS" to "0")::get) }
    }

    @Test
    fun `the command runs end to end against the stub and records passing models only`() {
        val dir = Files.createTempDirectory("bakeoff").toFile()
        val evals = File(dir, "evals.json").also { it.writeText(AiEvalRecords(emptyList()).toJson()) }
        bakeOff(arrayOf("--models", "claude-like:1b,babble:1b", "--base-url", "http://127.0.0.1:${server.address.port}", "--out", dir.path,
            "--write-eval-records", evals.path))
        val written = AiEvalRecords.parse(evals.readText())
        assertEquals(listOf("claude-like:1b"), written.records.map { it.model }, "babble:1b gives no valid answers, so it fails and isn't recorded")
        assertTrue(File(dir, "claude-like_1b.csv").exists())
        assertFalse(File(dir, "claude-like:1b.csv").exists())
    }
}
