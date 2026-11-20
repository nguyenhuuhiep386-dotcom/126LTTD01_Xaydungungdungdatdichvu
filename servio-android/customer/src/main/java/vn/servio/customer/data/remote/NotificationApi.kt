package vn.servio.customer.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.data.model.UnreadCount

/** M9 notification inbox of the customer app. Paths are relative to BASE_URL + "api/v1/". */
interface NotificationApi {
    /** #101 Newest first, every notification of this app (read and unread). */
    @GET("notifications")
    suspend fun list(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): ApiResponse<PagedResult<AppNotification>>

    /** #102 204. */
    @POST("notifications/{id}/read")
    suspend fun read(@Path("id") id: String): Response<Unit>

    /** #103 204. */
    @POST("notifications/read-all")
    suspend fun readAll(): Response<Unit>

    /** #104. */
    @GET("notifications/unread-count")
    suspend fun getUnreadCount(): ApiResponse<UnreadCount>
}
