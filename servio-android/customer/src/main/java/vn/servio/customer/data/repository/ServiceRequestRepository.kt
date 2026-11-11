package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.remote.ServiceRequestApi
import javax.inject.Inject
import javax.inject.Singleton

/** Requests of the signed-in customer (CS-14 list; KH-3 adds detail and cancel here). */
interface ServiceRequestRepository {
    /** #37, newest first. [statuses] empty = every status. [page] starts at 1. */
    suspend fun getMyRequests(
        statuses: Set<ServiceRequestStatus>,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): ApiResult<PagedResult<ServiceRequestSummary>>

    companion object {
        const val PAGE_SIZE = 20
    }
}

@Singleton
class RemoteServiceRequestRepository @Inject constructor(
    private val api: ServiceRequestApi,
) : ServiceRequestRepository {

    override suspend fun getMyRequests(
        statuses: Set<ServiceRequestStatus>,
        page: Int,
        pageSize: Int,
    ): ApiResult<PagedResult<ServiceRequestSummary>> =
        apiCall { api.getMine(statuses.joinToString(",") { it.name }.ifEmpty { null }, page, pageSize) }
}
