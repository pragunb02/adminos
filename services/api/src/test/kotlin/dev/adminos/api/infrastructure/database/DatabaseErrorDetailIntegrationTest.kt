package dev.adminos.api.infrastructure.database

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-628: with the production pool settings, Postgres errors never quote row values (so logs can't either). */
@Tag("integration")
class DatabaseErrorDetailIntegrationTest {

    @Test
    fun `a unique violation names the constraint but not the duplicated value`() {
        val value = "ravi-" + UUID.randomUUID() + "@example.com"
        PostgresTestDatabase.dataSource.connection.use { c ->
            c.createStatement().use { it.execute("CREATE TEMP TABLE aos628_probe (v text CONSTRAINT uq_aos628_probe UNIQUE)") }
            c.prepareStatement("INSERT INTO aos628_probe (v) VALUES (?)").use { s ->
                s.setString(1, value)
                s.executeUpdate()
                val e = assertFailsWith<SQLException> { s.executeUpdate() }
                assertTrue("uq_aos628_probe" in e.message.orEmpty(), e.message)
                assertFalse(value in e.message.orEmpty() || value in e.toString(), "the value must not be in the message")
            }
            c.rollback()
        }
    }
}
