package dev.adminos.api.infrastructure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AUD-02 (AOS-615): the Jackson modules that arrive transitively stay at or above the release that fixes the
 * 2026-09/10 advisories (a BOM in the build pins them), and the unused second JWT library stays out.
 */
class DependencyFloorTest {

    private fun atLeast(version: com.fasterxml.jackson.core.Version, major: Int, minor: Int, patch: Int) =
        compareValuesBy(version, com.fasterxml.jackson.core.Version(major, minor, patch, null, null, null),
            { it.majorVersion }, { it.minorVersion }, { it.patchLevel }) >= 0

    @Test
    fun `jackson core and databind are at least the patched release`() {
        assertTrue(atLeast(com.fasterxml.jackson.core.json.PackageVersion.VERSION, 2, 22, 3), "jackson-core ${com.fasterxml.jackson.core.json.PackageVersion.VERSION}")
        assertTrue(atLeast(com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION, 2, 22, 3), "jackson-databind ${com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION}")
    }

    @Test
    fun `the unused auth0 JWT library is not on the classpath`() {
        assertFalse(runCatching { Class.forName("com.auth0.jwt.JWT") }.isSuccess)
    }
}
