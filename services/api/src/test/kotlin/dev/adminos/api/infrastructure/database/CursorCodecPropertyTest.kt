package dev.adminos.api.infrastructure.database

import net.jqwik.api.*

/**
 * Property 1: Cursor Encoding Round-Trip
 *
 * For any safe sort key string and direction in {"asc", "desc"},
 * decode(encode(sortKey, direction)) returns the original (sortKey, direction).
 *
 * **Validates: Requirements 1.7**
 */
class CursorCodecPropertyTest {

    @Property(tries = 200)
    fun `decode of encode returns original sort key and direction`(
        @ForAll("safeSortKeys") sortKey: String,
        @ForAll("directions") direction: String
    ) {
        val encoded = CursorCodec.encode(sortKey, direction)
        val (decodedKey, decodedDir) = CursorCodec.decode(encoded)

        assert(decodedKey == sortKey) {
            "Sort key mismatch: expected '$sortKey', got '$decodedKey'"
        }
        assert(decodedDir == direction) {
            "Direction mismatch: expected '$direction', got '$decodedDir'"
        }
    }

    @Provide
    fun safeSortKeys(): Arbitrary<String> {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .withChars('-', '_', '.', ' ')
            .ofMinLength(1)
            .ofMaxLength(100)
    }

    @Provide
    fun directions(): Arbitrary<String> {
        return Arbitraries.of("asc", "desc")
    }
}
