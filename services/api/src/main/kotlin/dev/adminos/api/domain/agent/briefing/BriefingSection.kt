package dev.adminos.api.domain.agent.briefing

import dev.adminos.api.domain.analytics.DateRange
import java.time.ZoneId
import java.util.UUID

/** What a section adds to a weekly briefing: its lines (appended to the text) and a structured copy. */
data class BriefingSectionOutput(val key: String, val lines: List<String>, val structured: Map<String, Any?>) {
    init {
        require(key.matches(Regex("[a-z][a-zA-Z0-9]{1,40}"))) { "A section key is camelCase, was '$key'" }
    }
}

/**
 * A part of the weekly briefing owned by another feature (AOS-906): the spending coach's report card is one.
 * The briefing stays one notification a week (D-80: few nudges); a new section is one class registered in Koin.
 * Sections are deterministic like the briefing itself (D-57): every number in [BriefingSectionOutput.lines] is a fact.
 */
interface BriefingSection {
    /** @return null when the section has nothing to say this week */
    suspend fun render(userId: UUID, period: DateRange, zone: ZoneId): BriefingSectionOutput?
}
