package vn.servio.customer.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.CreateDisputeBody
import vn.servio.customer.data.model.Dispute

/** Disputes of an order (KH-7, CS-33). Paths are relative to BASE_URL + "api/v1/". */
interface DisputeApi {
    /**
     * #146 201. 400 VALIDATION_ERROR (field reason / description / evidenceUrls), 400 CONTACT_INFO_NOT_ALLOWED,
     * 404, 409 DISPUTE_ALREADY_OPEN, 422 DISPUTE_NOT_ALLOWED (status, or the window after completion passed).
     */
    @POST("orders/{id}/disputes")
    suspend fun create(@Path("id") orderId: String, @Body body: CreateDisputeBody): ApiResponse<Dispute>

    /** #147 Every dispute of the order, newest first (closed ones too). */
    @GET("orders/{id}/disputes")
    suspend fun list(@Path("id") orderId: String): ApiResponse<List<Dispute>>
}
