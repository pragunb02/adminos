package dev.adminos.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AOS-636: the repo is public, so test data must never carry a real person's address. Every email-like
 * value in test code and fixtures (API, workers, tools, web unit and e2e) must be on a reserved
 * domain (RFC 2606/6761: example.com/.net/.org, *.example, *.test, *.invalid, *.localhost) or belong
 * to an institution (a bank's alert sender, a Google service account). Personal mailbox providers
 * are refused outright, and so is an "example" domain that isn't reserved (example.in is a real one).
 */
class FixturePersonalDataTest {

    private val personalMailboxProviders = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "outlook.com", "hotmail.com",
        "live.com", "icloud.com", "me.com", "mac.com", "rediffmail.com", "protonmail.com", "proton.me", "aol.com",
    )
    private val reservedExample = Regex("""(^|\.)example\.(com|net|org)$""")
    private val email = Regex("""[A-Za-z0-9._%+\-]+@([A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+)""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "services/api").isDirectory && File(dir, "apps/web").isDirectory)) dir = dir.parentFile
        return checkNotNull(dir) { "repo root not found above ${File("").absolutePath}" }
    }

    private fun isFixture(path: String): Boolean =
        path.startsWith("services/api/src/test/") ||
            (path.startsWith("services/workers/") && (path.endsWith("_test.go") || "/testdata/" in path)) ||
            (path.startsWith("tools/") && (path.endsWith("_test.go") || "/testdata/" in path)) ||
            path.startsWith("apps/web/e2e/") || path.startsWith("apps/web/test/") ||
            (path.startsWith("apps/web/src/") && ".test." in path) ||
            path.startsWith("contracts/")

    /** The domain a value is on is refused when it is someone's mailbox, or an "example" that isn't reserved. */
    internal fun refused(domain: String): Boolean {
        val d = domain.lowercase().trimEnd('.')
        if (d in personalMailboxProviders) return true
        val looksLikeExample = d.split('.').any { it == "example" }
        return looksLikeExample && !reservedExample.containsMatchIn(d) && !d.endsWith(".example")
    }

    @Test
    fun `the guard refuses personal mailboxes and unreserved example domains and allows reserved ones`() {
        assertTrue(refused("gmail.com") && refused("ICLOUD.com") && refused("example.in") && refused("example.co.in"))
        assertTrue(!refused("example.com") && !refused("mail.example.org") && !refused("evil.example") && !refused("hdfcbank.net"))
    }

    @Test
    fun `test fixtures use reserved or institutional email domains only`() {
        val root = repoRoot()
        val exts = setOf("kt", "go", "ts", "tsx", "json", "csv", "txt", "eml", "sql")
        val files = sequenceOf("services/api/src/test", "services/workers", "tools", "apps/web/e2e", "apps/web/test", "apps/web/src", "contracts")
            .map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().onEnter { it.name != "node_modules" && it.name != "build" } }
            .filter { it.isFile && it.extension in exts }
            .filter { isFixture(it.relativeTo(root).invariantSeparatorsPath) }
            .toList()
        assertTrue(files.size > 100, "Guard scanned only ${files.size} files; scanning is broken")

        val offenders = files.flatMap { f ->
            f.readLines().withIndex().flatMap { (i, line) ->
                email.findAll(line).filter { refused(it.groupValues[1]) }
                    .map { "${f.relativeTo(root).invariantSeparatorsPath}:${i + 1} uses domain ${it.groupValues[1]}" }
            }
        }
        assertTrue(offenders.isEmpty(), "Use a reserved domain (owner@example.com) in test data:\n" + offenders.joinToString("\n"))
    }
}
