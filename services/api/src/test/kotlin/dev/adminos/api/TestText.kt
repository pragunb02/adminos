package dev.adminos.api

private val UUID_TEXT = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")

/**
 * [this] with every UUID replaced by `<id>`. "Never prints" checks look for digits (an account's last 4, an amount),
 * and a random id's hex can hold the same digits by chance, which made such tests flaky.
 */
fun String.withoutUuids(): String = replace(UUID_TEXT, "<id>")

/** Whether [token] appears in [this] on its own: not inside a longer run of letters or digits. */
fun String.hasToken(token: String): Boolean = Regex("(?<![0-9A-Za-z])${Regex.escape(token)}(?![0-9A-Za-z])").containsMatchIn(this)
