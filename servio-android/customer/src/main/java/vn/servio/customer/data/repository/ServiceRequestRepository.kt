package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.customer.data.model.CancelRequestBody
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.remote.ServiceRequestApi
import javax.inject.Inject
import javax.inject.Singleton

/** Requests of the signed-in customer and the quotes on them (CS-14, CS-15, CS-16). */
interface ServiceRequestRepository {
    /** #37, newest first. [statuses] empty = every status. [page] starts at 1. */
    suspend fun getMyRequests(
        statuses: Set<ServiceRequestStatus>,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): ApiResult<PagedResult<ServiceRequestSummary>>

    /** #38. */
    suspend fun getDetail(requestId: String): ApiResult<ServiceRequestDetail>

    /** #40. [reason] is optional (blank = none). */
    suspend fun cancel(requestId: String, reason: String?): ApiResult<Unit>

    /** #42 in one call: up to [MAX_QUOTES] quotes (the server's page limit), sorted by the server. */
    suspend fun getQuotes(requestId: String, sort: QuoteSort): ApiResult<PagedResult<CustomerQuote>>

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_QUOTES = 50
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

    override suspend fun getDetail(requestId: String): ApiResult<ServiceRequestDetail> = apiCall { api.getDetail(requestId) }

    override suspend fun cancel(requestId: String, reason: String?): ApiResult<Unit> =
        apiCallNoContent { api.cancel(requestId, CancelRequestBody(reason?.trim()?.ifEmpty { null })) }

    override suspend fun getQuotes(requestId: String, sort: QuoteSort): ApiResult<PagedResult<CustomerQuote>> =
        apiCall { api.getQuotes(requestId, sort.name, page = 1, pageSize = ServiceRequestRepository.MAX_QUOTES) }
}
