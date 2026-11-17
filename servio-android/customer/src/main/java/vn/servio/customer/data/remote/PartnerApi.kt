package vn.servio.customer.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.PartnerPublic

/** Partners as a customer sees them. Paths are relative to BASE_URL + "api/v1/". */
interface PartnerApi {
    /** #27. 404 when the customer has no quote, chat or order with this partner. */
    @GET("partners/{id}")
    suspend fun getPublic(@Path("id") id: String): ApiResponse<PartnerPublic>
}
