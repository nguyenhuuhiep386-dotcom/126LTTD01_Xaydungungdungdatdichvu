package vn.servio.customer.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.CreateServiceRequestRequest
import vn.servio.customer.data.model.CreatedRequest
import vn.servio.customer.data.model.ServiceRequestSummary

// Paths are relative to BASE_URL + "api/v1/". Endpoint numbers (#n) refer to spec section 6.

/** Service requests of the signed-in customer (BE-2). #36 returns 201 with the envelope. */
interface ServiceRequestApi {
    /**
     * [idempotencyKey]: same key for a retry of the same body (spec 6.1). The BE-2 server does not read it yet, so for
     * now only the app's "posting" flag keeps a double tap from creating two requests.
     */
    @POST("service-requests")
    suspend fun create(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateServiceRequestRequest,
    ): ApiResponse<CreatedRequest>

    /** #37. [status]: comma-separated status names (e.g. `OPEN,DRAFT`), null = all. The server caps pageSize at 50. */
    @GET("service-requests/me")
    suspend fun getMine(
        @Query("status") status: String?,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): ApiResponse<PagedResult<ServiceRequestSummary>>
}
