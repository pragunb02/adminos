package dev.adminos.api.domain.scheduling

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock a test moves by hand, so schedules and retries are deterministic (no sleeps). */
class TestClock(var now: Instant) : Clock() {
    fun advance(by: Duration) { now = now.plus(by) }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
