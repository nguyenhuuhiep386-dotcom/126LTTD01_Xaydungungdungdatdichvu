package vn.servio.customer.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import vn.servio.core.network.ApiErrorDto
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.CreateServiceRequestRequest
import vn.servio.customer.data.model.CreatedRequest
import vn.servio.customer.data.remote.ServiceRequestApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posting a request (CS-11, #36). Kept apart from [ServiceRequestRepository] (CS-14 list, still fake until KH-3)
 * so the fake list does not need a fake "create".
 */
interface CreateRequestRepository {
    suspend fun createRequest(request: CreateServiceRequestRequest, idempotencyKey: String): ApiResult<CreatedRequest>
}

/**
 * Real API. Unlike other calls, a server error keeps the server's own code, field and message: #36 puts details in
 * the message only (the fee owed for OUTSTANDING_DEBT, the end of POSTING_RESTRICTED), and the core table would
 * replace some of them with a generic text. A body that cannot be read falls back to [apiCall]'s message.
 */
@Singleton
class RemoteCreateRequestRepository @Inject constructor(
    private val api: ServiceRequestApi,
) : CreateRequestRepository {

    override suspend fun createRequest(request: CreateServiceRequestRequest, idempotencyKey: String): ApiResult<CreatedRequest> {
        var serverError: ApiErrorDto? = null
        val result = apiCall {
            try {
                api.create(idempotencyKey, request)
            } catch (e: HttpException) {
                serverError = firstError(e)
                throw e
            }
        }
        val error = serverError
        return if (result is ApiResult.Failure && error != null) {
            ApiResult.Failure(error.code, error.message, error.field)
        } else {
            result
        }
    }

    private fun firstError(e: HttpException): ApiErrorDto? = runCatching {
        val body = e.response()?.errorBody()?.string() ?: return null
        errorJson.decodeFromString<ApiResponse<JsonElement>>(body).errors?.firstOrNull()?.takeIf { it.message.isNotBlank() }
    }.getOrNull()

    private companion object {
        val errorJson = Json { ignoreUnknownKeys = true }
    }
}
