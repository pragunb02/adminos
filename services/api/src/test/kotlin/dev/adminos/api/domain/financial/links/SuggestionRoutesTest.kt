package dev.adminos.api.domain.financial.links

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestionRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val me = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val other = User(id = UUID.randomUUID(), email = "o@example.com", googleId = "g-2", role = UserRole.OWNER)
    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val service = SuggestionService(links, txns)

    private fun seed(owner: UUID): TransactionLink = runBlocking {
        val a = txns.save(Transaction(userId = owner, sourceType = "sms", type = TransactionType.DEBIT, amount = BigDecimal("99.00"), transactedAt = Instant.now()))
        val b = txns.save(Transaction(userId = owner, sourceType = "manual", type = TransactionType.DEBIT, amount = BigDecimal("99.00"), transactedAt = Instant.now()))
        links.save(TransactionLink(userId = owner, transactionId = a.id, relatedTransactionId = b.id, source = "manual",
            kind = LinkKind.SUGGESTED, score = 7, reason = "same amount, same day", rulesVersion = TransactionMatcher.RULES_VERSION))
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { suggestionRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, user: User = me) =
        client.request("/api/v1/transactions/suggestions$path") { this.method = method; cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(user)) }

    @Test
    fun `list, confirm and reject`() = app {
        val l1 = seed(me.id); val l2 = seed(me.id)
        val list = call(HttpMethod.Get, "").bodyAsText()
        assertTrue(l1.id.toString() in list && "\"score\":7" in list && "\"related\"" in list, list)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/${l1.id}/confirm").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/${l1.id}/confirm").status, "already decided")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/${l2.id}/reject").status)
    }

    @Test
    fun `users can't see or decide other users' suggestions`() = app {
        val theirs = seed(other.id)
        assertTrue(theirs.id.toString() !in call(HttpMethod.Get, "").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/${theirs.id}/confirm").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/not-a-uuid/reject").status)
    }
}
