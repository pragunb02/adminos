package dev.adminos.api.security

import dev.adminos.api.InfraIngestTestKit
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.module
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID

/**
 * The real application (every module, every route, production plugins) in its in-memory configuration, for the
 * authorization attack tests (AOS-619). [block] gets the running [Application] so a test can reach its Koin graph.
 */
object SecurityTestKit {
    val config = InfraIngestTestKit.config.copy(scheduler = dev.adminos.api.config.SchedulerConfig(enabled = false))

    fun realApp(block: suspend ApplicationTestBuilder.(Application) -> Unit) = testApplication {
        var app: Application? = null
        environment { this.config = MapApplicationConfig() }
        application { module(SecurityTestKit.config); app = this }
        startApplication()
        block(app ?: error("the application did not start"))
    }

    fun newUser(name: String) = User(
        id = UUID.randomUUID(), email = "$name-${UUID.randomUUID()}@example.com", googleId = "g-${UUID.randomUUID()}", role = UserRole.OWNER
    )

    fun tokenFor(user: User): String = InfraIngestTestKit.tokenFor(user)
}
