package vn.servio.customer.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.model.PartnerReview

/** Partners as a customer sees them. Paths are relative to BASE_URL + "api/v1/". */
interface PartnerApi {
    /** #27. 404 when the customer has no quote, chat or order with this partner. */
    @GET("partners/{id}")
    suspend fun getPublic(@Path("id") id: String): ApiResponse<PartnerPublic>

    /** #28. Same 404 rule as #27. pageSize ≤ 50. */
    @GET("partners/{id}/reviews")
    suspend fun getReviews(
        @Path("id") id: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): ApiResponse<PagedResult<PartnerReview>>
}
