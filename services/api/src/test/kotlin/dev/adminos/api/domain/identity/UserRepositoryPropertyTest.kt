package dev.adminos.api.domain.identity

import kotlinx.coroutines.runBlocking
import net.jqwik.api.*
import java.time.Instant
import java.util.UUID

/**
 * Property 2: Repository Save/FindById Round-Trip (InMemory)
 *
 * For any valid User, save(user) then findById(user.id) returns a user equal to the saved one.
 *
 * **Validates: Requirements 1.8, 8.6**
 */
class UserRepositoryPropertyTest {

    @Property(tries = 200)
    fun `save then findById returns the same user`(
        @ForAll("validUsers") user: User
    ) {
        val repo = InMemoryUserRepository()

        runBlocking {
            val saved = repo.save(user)
            val found = repo.findById(user.id)

            assert(found != null) { "findById returned null for user ${user.id}" }
            assert(found == saved) {
                "Round-trip mismatch:\n  saved:  $saved\n  found:  $found"
            }
        }
    }

    @Provide
    fun validUsers(): Arbitrary<User> {
        val ids = Arbitraries.create { UUID.randomUUID() }
        val emails = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20)
            .map { "$it@example.com" }
        val names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30)
            .injectNull(0.3)
        val googleIds = Arbitraries.strings().alpha().numeric().ofMinLength(10).ofMaxLength(30)
        val phones = Arbitraries.strings().numeric().ofMinLength(10).ofMaxLength(15)
            .injectNull(0.3)
        val countries = Arbitraries.of("IN", "US", "GB", "DE", "JP")
        val timezones = Arbitraries.of("Asia/Kolkata", "America/New_York", "Europe/London", "UTC")
        val statuses = Arbitraries.of(*OnboardingStatus.values())
        val roles = Arbitraries.of(*UserRole.values())
        val actives = Arbitraries.of(true, false)

        // Combine first 8, then flatMap the remaining 2
        return Combinators.combine(
            ids, emails, names, googleIds, phones, countries, timezones, statuses
        ).`as` { id, email, name, googleId, phone, country, tz, status ->
            Triple(
                Triple(id, email, name),
                Triple(googleId, phone, country),
                Pair(tz, status)
            )
        }.flatMap { parts ->
            Combinators.combine(roles, actives).`as` { role, active ->
                val (idPart, miscPart, tzStatus) = parts
                val now = Instant.now()
                User(
                    id = idPart.first,
                    email = idPart.second,
                    name = idPart.third,
                    googleId = miscPart.first,
                    phone = miscPart.second,
                    country = miscPart.third,
                    timezone = tzStatus.first,
                    onboardingStatus = tzStatus.second,
                    role = role,
                    isActive = active,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
            }
        }
    }
}
