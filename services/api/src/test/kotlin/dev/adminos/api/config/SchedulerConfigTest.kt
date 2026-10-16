package dev.adminos.api.config

import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerConfigTest {
    private fun from(vararg env: Pair<String, String>) = SchedulerConfig.from { env.toMap()[it] }

    @Test
    fun `by default the scheduler is on, ticks every 30 seconds, in India time`() {
        assertEquals(SchedulerConfig(enabled = true, tick = Duration.ofSeconds(30), zone = ZoneId.of("Asia/Kolkata")), from())
        assertEquals(SchedulerConfig(), from("SCHEDULER_ENABLED" to " ", "SCHEDULER_TICK_SECONDS" to "", "SCHEDULER_ZONE" to ""))
    }

    @Test
    fun `it can be switched off, and each setting can be changed`() {
        val c = from("SCHEDULER_ENABLED" to "FALSE", "SCHEDULER_TICK_SECONDS" to "5", "SCHEDULER_ZONE" to "Europe/London")
        assertFalse(c.enabled)
        assertEquals(Duration.ofSeconds(5), c.tick)
        assertEquals(ZoneId.of("Europe/London"), c.zone)
        assertTrue(from("SCHEDULER_ENABLED" to "true").enabled)
    }

    @Test
    fun `invalid values stop start-up with a message naming the setting`() {
        listOf(
            "SCHEDULER_ENABLED" to "yes",
            "SCHEDULER_TICK_SECONDS" to "thirty",
            "SCHEDULER_TICK_SECONDS" to "0",
            "SCHEDULER_ZONE" to "Mars/Olympus"
        ).forEach { (key, value) ->
            val e = assertFailsWith<IllegalArgumentException>("$key=$value") { from(key to value) }
            assertTrue(e.message.orEmpty().contains(key), e.message)
        }
    }
}
