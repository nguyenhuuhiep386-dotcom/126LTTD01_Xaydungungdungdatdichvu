package vn.servio.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import java.io.IOException

/** Result of a repository call. UI only needs [Failure.message]; [Failure.code] is for special handling. */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val code: String, val message: String, val field: String? = null) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Wraps a Retrofit call: unwraps the envelope and turns every error into [ApiResult.Failure]
 * with a Vietnamese message. Repositories should call APIs only through this function.
 */
suspend fun <T> apiCall(block: suspend () -> ApiResponse<T>): ApiResult<T> = try {
    val response = block()
    val data = response.data
    if (response.success && data != null) {
        ApiResult.Success(data)
    } else {
        failureFrom(response.errors, response.message)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    val body = e.response()?.errorBody()?.string()
    val envelope = body?.let { runCatching { errorJson.decodeFromString<ApiResponse<JsonElement>>(it) }.getOrNull() }
    failureFrom(envelope?.errors, envelope?.message, httpStatus = e.code())
} catch (e: IOException) {
    ApiResult.Failure(ErrorMessages.NETWORK_ERROR, ErrorMessages.forCode(ErrorMessages.NETWORK_ERROR))
} catch (e: Exception) {
    ApiResult.Failure(ErrorMessages.UNKNOWN, ErrorMessages.forCode(ErrorMessages.UNKNOWN))
}

/** For endpoints that return 204 No Content (Retrofit `Response<Unit>`). */
suspend fun apiCallNoContent(block: suspend () -> retrofit2.Response<Unit>): ApiResult<Unit> = try {
    val response = block()
    if (response.isSuccessful) {
        ApiResult.Success(Unit)
    } else {
        val body = response.errorBody()?.string()
        val envelope = body?.let { runCatching { errorJson.decodeFromString<ApiResponse<JsonElement>>(it) }.getOrNull() }
        failureFrom(envelope?.errors, envelope?.message, httpStatus = response.code())
    }
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ApiResult.Failure(ErrorMessages.NETWORK_ERROR, ErrorMessages.forCode(ErrorMessages.NETWORK_ERROR))
}

private fun failureFrom(errors: List<ApiErrorDto>?, message: String?, httpStatus: Int? = null): ApiResult.Failure {
    val first = errors?.firstOrNull()
    val code = first?.code ?: if (httpStatus == 401) "UNAUTHENTICATED" else ErrorMessages.UNKNOWN
    val text = first?.message ?: message ?: ErrorMessages.forCode(code)
    return ApiResult.Failure(code, ErrorMessages.forCode(code, fallback = text), first?.field)
}
