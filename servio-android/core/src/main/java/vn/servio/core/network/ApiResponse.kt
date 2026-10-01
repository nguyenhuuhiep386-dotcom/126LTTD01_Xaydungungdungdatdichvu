package vn.servio.core.network

import kotlinx.serialization.Serializable

/** Server envelope (spec 6.1): every endpoint returns `{ success, data, message, errors, meta }`. */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val errors: List<ApiErrorDto>? = null,
)

@Serializable
data class ApiErrorDto(
    val code: String,
    val field: String? = null,
    val message: String,
)

@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)
