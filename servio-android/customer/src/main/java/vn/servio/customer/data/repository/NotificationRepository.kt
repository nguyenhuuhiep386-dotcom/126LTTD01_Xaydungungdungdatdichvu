package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.UnreadCount
import vn.servio.customer.data.remote.NotificationApi
import javax.inject.Inject
import javax.inject.Singleton

/** In-app notifications of the customer (M9). For now only the unread counter of CS-29. */
interface NotificationRepository {
    /** #104. */
    suspend fun unreadCount(): ApiResult<UnreadCount>
}

@Singleton
class RemoteNotificationRepository @Inject constructor(
    private val api: NotificationApi,
) : NotificationRepository {
    override suspend fun unreadCount(): ApiResult<UnreadCount> = apiCall { api.getUnreadCount() }
}
