package dev.adminos.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JUnit silently skips @Test methods that return a value (e.g. `= runBlocking { assertNotNull(x) }`).
 * That hid 9 tests until AOS-105. This guard fails the build if any test method returns non-void.
 */
class TestConventionsTest {

    @Test
    fun `every test method returns Unit so JUnit does not silently skip it`() {
        val root = File(TestConventionsTest::class.java.protectionDomain.codeSource.location.toURI())
        val testAnnotations = setOf("org.junit.jupiter.api.Test", "kotlin.test.Test", "net.jqwik.api.Property", "net.jqwik.api.Example")
        val testMethods = root.walkTopDown()
            .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .mapNotNull { runCatching { Class.forName(it, false, javaClass.classLoader) }.getOrNull() }
            .flatMap { cls -> cls.declaredMethods.asSequence().map { cls to it } }
            .filter { (_, m) -> m.annotations.any { it.annotationClass.qualifiedName in testAnnotations } }
            .toList()
        assertTrue(testMethods.size > 100, "Guard found only ${testMethods.size} test methods; scanning is broken")

        val offenders = testMethods
            .filter { (_, m) -> m.returnType != Void.TYPE }
            .map { (cls, m) -> "${cls.simpleName}.${m.name} returns ${m.returnType.simpleName}" }
        assertTrue(offenders.isEmpty(), "Test methods must return Unit (use runBlocking<Unit> { }):\n" + offenders.joinToString("\n"))
    }
}
