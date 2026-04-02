package dev.adminos.api.domain.common

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Meta(
    val requestId: String,
    val timestamp: String
)

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
    val pagination: Pagination? = null,
    val meta: Meta
) {
    companion object {
        fun <T> success(data: T, requestId: String): ApiResponse<T> = ApiResponse(
            success = true,
            data = data,
            meta = Meta(requestId = requestId, timestamp = Instant.now().toString())
        )

        fun <T> paginated(data: T, pagination: Pagination, requestId: String): ApiResponse<T> = ApiResponse(
            success = true,
            data = data,
            pagination = pagination,
            meta = Meta(requestId = requestId, timestamp = Instant.now().toString())
        )

        fun error(error: ApiError, requestId: String): ApiResponse<Nothing> = ApiResponse(
            success = false,
            error = error,
            meta = Meta(requestId = requestId, timestamp = Instant.now().toString())
        )
    }
}

@Serializable
data class Pagination(
    val cursor: String? = null,
    val hasMore: Boolean = false,
    val total: Long = 0
)
