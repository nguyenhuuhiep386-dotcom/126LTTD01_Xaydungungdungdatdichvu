package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.map
import vn.servio.customer.data.model.AcceptQuotesBody
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.data.model.CancelOrderBody
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.CancelResult
import vn.servio.customer.data.model.CreatedOrder
import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderListFilter
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.data.model.RespondArrivalBody
import vn.servio.customer.data.model.RespondExtraChargeBody
import vn.servio.customer.data.model.Tracking
import vn.servio.customer.data.remote.OrderApi
import javax.inject.Inject
import javax.inject.Singleton

/** Orders (CS-18 … CS-22): list, detail, cancel, tracking, the answer to "Thợ báo đã đến" and to extra charges. */
interface OrderRepository {
    /** #43: choose [quoteId] at [rowVersion] (from #42), paid in cash. */
    suspend fun acceptQuote(requestId: String, quoteId: String, rowVersion: String): ApiResult<CreatedOrder>

    /** #62, one CS-19 tab. */
    suspend fun getMyOrders(filter: OrderListFilter, page: Int, pageSize: Int = PAGE_SIZE): ApiResult<PagedResult<OrderSummary>>

    /** #63. */
    suspend fun getOrder(orderId: String): ApiResult<OrderDetail>

    /** #151. */
    suspend fun cancelPreview(orderId: String): ApiResult<CancelPreview>

    /** #72 with the rowVersion of the preview the customer agreed to. */
    suspend fun cancel(orderId: String, reason: String, rowVersion: String): ApiResult<CancelResult>

    /** #76 for one assignment; null when the server has no live entry for it (e.g. cancelled). */
    suspend fun getTracking(orderId: String, assignmentId: String): ApiResult<Tracking?>

    /** #179. */
    suspend fun respondArrival(confirmation: ArrivalConfirmation, confirmed: Boolean): ApiResult<ArrivalConfirmation>

    /** #74 without a note. */
    suspend fun respondExtraCharge(orderId: String, extraChargeId: String, approved: Boolean): ApiResult<ExtraCharge>

    /** maxDebt of #87: unpaid cancellation fees above it block new posts. Null when the server does not say. */
    suspend fun cancelDebtLimit(): ApiResult<Long?>

    companion object {
        const val PAGE_SIZE = 20
    }
}

@Singleton
class RemoteOrderRepository @Inject constructor(
    private val api: OrderApi,
) : OrderRepository {
    override suspend fun acceptQuote(requestId: String, quoteId: String, rowVersion: String): ApiResult<CreatedOrder> =
        apiCall { api.acceptQuotes(requestId, AcceptQuotesBody.one(quoteId, rowVersion)) }

    override suspend fun getMyOrders(filter: OrderListFilter, page: Int, pageSize: Int): ApiResult<PagedResult<OrderSummary>> =
        apiCall { api.getMine(filter.name, page, pageSize) }

    override suspend fun getOrder(orderId: String): ApiResult<OrderDetail> = apiCall { api.getDetail(orderId) }

    override suspend fun cancelPreview(orderId: String): ApiResult<CancelPreview> = apiCall { api.cancelPreview(orderId) }

    override suspend fun cancel(orderId: String, reason: String, rowVersion: String): ApiResult<CancelResult> =
        apiCall { api.cancel(orderId, CancelOrderBody(reason, rowVersion)) }

    override suspend fun getTracking(orderId: String, assignmentId: String): ApiResult<Tracking?> =
        apiCall { api.getTracking(orderId, assignmentId) }.map { list -> list.firstOrNull { it.assignmentId.equals(assignmentId, ignoreCase = true) } }

    override suspend fun respondArrival(confirmation: ArrivalConfirmation, confirmed: Boolean): ApiResult<ArrivalConfirmation> =
        apiCall { api.respondArrival(confirmation.orderId, confirmation.assignmentId, confirmation.requestId, RespondArrivalBody(confirmed)) }

    override suspend fun respondExtraCharge(orderId: String, extraChargeId: String, approved: Boolean): ApiResult<ExtraCharge> =
        apiCall { api.respondExtraCharge(orderId, extraChargeId, RespondExtraChargeBody(approved)) }

    override suspend fun cancelDebtLimit(): ApiResult<Long?> = apiCall { api.getWallet() }.map { it.maxDebt }
}
