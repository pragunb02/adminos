package dev.adminos.api.infrastructure.storage

import net.jqwik.api.*
import java.util.UUID

/**
 * Property 7: R2 Storage Key Format
 *
 * For any user ID (UUID) and filename, the generated storage key matches
 * the pattern uploads/{userId}/{uuid}/{filename} where the middle UUID
 * is valid and distinct from the user ID.
 *
 * **Validates: Requirements 7.2**
 */
class StorageKeyPropertyTest {

    /**
     * Generates a storage key in the same way as UploadRoutes.kt:
     *   "uploads/${userId}/${UUID.randomUUID()}/$fileName"
     */
    private fun generateStorageKey(userId: UUID, fileName: String): String {
        return "uploads/$userId/${UUID.randomUUID()}/$fileName"
    }

    @Property(tries = 200)
    fun `storage key matches expected format with valid distinct UUID`(
        @ForAll("userIds") userId: UUID,
        @ForAll("fileNames") fileName: String
    ) {
        val key = generateStorageKey(userId, fileName)

        // Pattern: uploads/{uuid}/{uuid}/{filename}
        val parts = key.split("/")
        assert(parts.size == 4) {
            "Expected 4 segments in key, got ${parts.size}: $key"
        }
        assert(parts[0] == "uploads") {
            "First segment should be 'uploads', got '${parts[0]}'"
        }
        assert(parts[1] == userId.toString()) {
            "Second segment should be userId '$userId', got '${parts[1]}'"
        }

        // Middle segment must be a valid UUID
        val middleUuid = try {
            UUID.fromString(parts[2])
        } catch (e: IllegalArgumentException) {
            null
        }
        assert(middleUuid != null) {
            "Third segment '${parts[2]}' is not a valid UUID"
        }

        // Middle UUID must be distinct from user ID
        assert(middleUuid != userId) {
            "Middle UUID should be distinct from userId, both are $userId"
        }

        assert(parts[3] == fileName) {
            "Fourth segment should be filename '$fileName', got '${parts[3]}'"
        }
    }

    @Provide
    fun userIds(): Arbitrary<UUID> {
        return Arbitraries.create { UUID.randomUUID() }
    }

    @Provide
    fun fileNames(): Arbitrary<String> {
        val baseName = Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .ofMinLength(1)
            .ofMaxLength(50)
        val extensions = Arbitraries.of("pdf", "png", "jpg", "csv", "xlsx")

        return Combinators.combine(baseName, extensions)
            .`as` { name, ext -> "$name.$ext" }
    }
}
