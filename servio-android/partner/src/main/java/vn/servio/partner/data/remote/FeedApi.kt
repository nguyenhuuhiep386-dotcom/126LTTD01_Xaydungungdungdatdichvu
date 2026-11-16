package vn.servio.partner.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.PagedResult
import vn.servio.partner.data.model.FeedItem
import vn.servio.partner.data.model.HeartbeatRequest
import vn.servio.partner.data.model.OnlineStatus
import vn.servio.partner.data.model.OnlineStatusRequest
import vn.servio.partner.data.model.PartnerQuote
import vn.servio.partner.data.model.PartnerRequestView
import vn.servio.partner.data.model.SubmitQuoteRequest
import vn.servio.partner.data.model.WithdrawQuoteRequest

// Paths are relative to BASE_URL + "api/v1/". Endpoint numbers (#n) refer to spec section 6.

/** Newsfeed, request detail and quotes for the partner (DT-2). */
interface FeedApi {
    /** #46. `categoryIds` is repeated once per id. `sort`: DISTANCE or NEWEST. */
    @GET("feed")
    suspend fun getFeed(
        @Query("categoryIds") categoryIds: List<String>?,
        @Query("maxDistanceKm") maxDistanceKm: Double?,
        @Query("sort") sort: String?,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): ApiResponse<PagedResult<FeedItem>>

    /** #38, partner view (the server picks it from the X-Client-Platform header). */
    @GET("service-requests/{id}")
    suspend fun getRequest(@Path("id") id: String): ApiResponse<PartnerRequestView>

    /** #50 */
    @POST("service-requests/{id}/quotes")
    suspend fun submitQuote(@Path("id") requestId: String, @Body body: SubmitQuoteRequest): ApiResponse<PartnerQuote>

    /** #53. `status`: PENDING, ACCEPTED, REJECTED, WITHDRAWN or EXPIRED; null = all. */
    @GET("quotes/me")
    suspend fun listMyQuotes(
        @Query("status") status: String?,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): ApiResponse<PagedResult<PartnerQuote>>

    /** #52 */
    @POST("quotes/{id}/withdraw")
    suspend fun withdrawQuote(@Path("id") id: String, @Body body: WithdrawQuoteRequest): Response<Unit>
}

/** Online toggle and heartbeat (#25, #26). */
interface PresenceApi {
    @POST("partners/me/online-status")
    suspend fun setOnline(@Body body: OnlineStatusRequest): ApiResponse<OnlineStatus>

    @POST("partners/me/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): Response<Unit>
}
