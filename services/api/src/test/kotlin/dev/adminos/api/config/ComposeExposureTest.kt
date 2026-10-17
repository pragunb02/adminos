package dev.adminos.api.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AOS-703: nothing in `docker-compose.yml` is published on a bare host port. Every published port is bound to
 * the loopback interface (`127.0.0.1:host:container`). Ollama in particular has no authentication, so a bare
 * `11434:11434` would expose a model server to the whole network.
 */
class ComposeExposureTest {

    private val compose = File("../../docker-compose.yml").takeIf { it.exists() } ?: File("docker-compose.yml")

    /** The `- "…"` entries under every `ports:` key, with the service they belong to. */
    private fun publishedPorts(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var service = ""
        var portsIndent: Int? = null
        for (line in text.lines()) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            if (indent == 2 && line.trimEnd().endsWith(":")) service = line.trim().removeSuffix(":")
            val current = portsIndent
            when {
                line.trim() == "ports:" -> portsIndent = indent
                current != null && indent > current && line.trim().startsWith("- ") ->
                    out += service to line.trim().removePrefix("- ").substringBefore("#").trim().trim('"', '\'')
                current != null && indent <= current -> portsIndent = null
            }
        }
        return out
    }

    @Test
    fun `every published port is bound to loopback only`() {
        val ports = publishedPorts(compose.readText())
        assertTrue(ports.isNotEmpty(), "found no ports in ${compose.path}")
        val bare = ports.filterNot { (_, p) -> p.startsWith("127.0.0.1:") }
        assertEquals(emptyList(), bare, "publish only on 127.0.0.1 (D-69)")
        assertTrue(ports.contains("ollama" to "127.0.0.1:11434:11434"), "the ollama profile publishes on loopback only: $ports")
    }

    @Test
    fun `the parser sees bare ports and their services`() {
        val sample = """
            services:
              a:
                ports:
                  - "127.0.0.1:1:1"
                  - 2:2   # bare
                environment:
                  X: "3:3"
              b:
                image: x
        """.trimIndent()
        assertEquals(listOf("a" to "127.0.0.1:1:1", "a" to "2:2"), publishedPorts(sample))
    }
}
