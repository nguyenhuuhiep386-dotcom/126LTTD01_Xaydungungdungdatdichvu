package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.data.model.UnreadCount
import vn.servio.customer.data.remote.NotificationApi
import javax.inject.Inject
import javax.inject.Singleton

/** In-app notifications of the customer (M9): CS-31 list, the bell of CS-06 and the counter of CS-29. */
interface NotificationRepository {
    /** #101, [page] from 1, [PAGE_SIZE] rows. */
    suspend fun list(page: Int): ApiResult<PagedResult<AppNotification>>

    /** #102. */
    suspend fun read(id: String): ApiResult<Unit>

    /** #103. */
    suspend fun readAll(): ApiResult<Unit>

    /** #104. */
    suspend fun unreadCount(): ApiResult<UnreadCount>

    companion object {
        const val PAGE_SIZE = 20
    }
}

@Singleton
class RemoteNotificationRepository @Inject constructor(
    private val api: NotificationApi,
) : NotificationRepository {
    override suspend fun list(page: Int): ApiResult<PagedResult<AppNotification>> =
        apiCall { api.list(page, NotificationRepository.PAGE_SIZE) }

    override suspend fun read(id: String): ApiResult<Unit> = apiCallNoContent { api.read(id) }

    override suspend fun readAll(): ApiResult<Unit> = apiCallNoContent { api.readAll() }

    override suspend fun unreadCount(): ApiResult<UnreadCount> = apiCall { api.getUnreadCount() }
}
