package vn.servio.customer.data.remote

import retrofit2.http.Body
import retrofit2.http.POST
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.CreateServiceRequestRequest
import vn.servio.customer.data.model.CreatedRequest

// Paths are relative to BASE_URL + "api/v1/". Endpoint numbers (#n) refer to spec section 6.

/** Service requests of the signed-in customer. #36 comes with BE-2 (returns 201 with the envelope). */
interface ServiceRequestApi {
    @POST("service-requests")
    suspend fun create(@Body body: CreateServiceRequestRequest): ApiResponse<CreatedRequest>
}
