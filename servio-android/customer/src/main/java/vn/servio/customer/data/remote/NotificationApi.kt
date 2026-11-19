package vn.servio.customer.data.remote

import retrofit2.http.GET
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.UnreadCount

/** M9 notification inbox of the customer app. Paths are relative to BASE_URL + "api/v1/". */
interface NotificationApi {
    /** #104. */
    @GET("notifications/unread-count")
    suspend fun getUnreadCount(): ApiResponse<UnreadCount>
}
