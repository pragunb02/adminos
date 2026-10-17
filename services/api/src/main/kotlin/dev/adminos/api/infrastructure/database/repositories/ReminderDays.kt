package dev.adminos.api.infrastructure.database.repositories

/** "Days before" sets as stored in TEXT columns (V21, AOS-503): {7, 3, 1} ⇄ "1,3,7"; empty ⇄ null. */
internal fun encodeDays(days: Set<Int>?): String? = days?.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")

/** Unreadable parts are skipped rather than failing a whole bill (the column is written only by [encodeDays]). */
internal fun decodeDays(text: String?): Set<Int>? =
    text?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }
