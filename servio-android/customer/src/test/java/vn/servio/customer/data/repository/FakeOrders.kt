package vn.servio.customer.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.CancelResult
import vn.servio.customer.data.model.CreatedOrder
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderListFilter
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.data.model.Tracking

/** Test server for the order screens: answers are set per test, calls are recorded. */
class FakeOrderRepository : OrderRepository {
    val lists = mutableMapOf<OrderListFilter, ApiResult<PagedResult<OrderSummary>>>()
    val listCalls = mutableListOf<Pair<OrderListFilter, Int>>()
    var order: ApiResult<OrderDetail>? = null
    var orderCalls = 0

    /** Answers of #151 in order; the last one repeats. */
    val previews = ArrayDeque<ApiResult<CancelPreview>>()
    var previewCalls = 0

    /** Answers of #72 in order; the last one repeats. */
    val cancels = ArrayDeque<ApiResult<CancelResult>>()
    val cancelCalls = mutableListOf<Triple<String, String, String>>()
    var debtLimit: ApiResult<Long?> = ApiResult.Success(100_000L)

    override suspend fun acceptQuote(requestId: String, quoteId: String, rowVersion: String): ApiResult<CreatedOrder> = error("unused")

    override suspend fun getMyOrders(filter: OrderListFilter, page: Int, pageSize: Int): ApiResult<PagedResult<OrderSummary>> {
        listCalls += filter to page
        return lists[filter] ?: ApiResult.Success(PagedResult(emptyList(), 0, page, pageSize, 0))
    }

    override suspend fun getOrder(orderId: String): ApiResult<OrderDetail> {
        orderCalls++
        return order ?: error("no order set")
    }

    override suspend fun cancelPreview(orderId: String): ApiResult<CancelPreview> {
        previewCalls++
        return if (previews.size > 1) previews.removeFirst() else previews.first()
    }

    override suspend fun cancel(orderId: String, reason: String, rowVersion: String): ApiResult<CancelResult> {
        cancelCalls += Triple(orderId, reason, rowVersion)
        return if (cancels.size > 1) cancels.removeFirst() else cancels.first()
    }

    override suspend fun cancelDebtLimit(): ApiResult<Long?> = debtLimit

    /** Answer of #76 for any assignment. */
    var tracking: ApiResult<Tracking?> = ApiResult.Success(null)
    var trackingCalls = 0

    override suspend fun getTracking(orderId: String, assignmentId: String): ApiResult<Tracking?> {
        trackingCalls++
        return tracking
    }

    /** Answers of #179 in order; the last one repeats. */
    val responds = ArrayDeque<ApiResult<ArrivalConfirmation>>()
    val respondCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun respondArrival(confirmation: ArrivalConfirmation, confirmed: Boolean): ApiResult<ArrivalConfirmation> {
        respondCalls += confirmation.requestId to confirmed
        return if (responds.size > 1) responds.removeFirst() else responds.first()
    }
}

/** Hub stand-in: tests emit an order id (or null for a reconnect), locations, and keep arrival requests in [arrivals]. */
class FakeOrderUpdates(val arrivals: ArrivalRequests = ArrivalRequests()) : OrderUpdates {
    val events = MutableSharedFlow<String?>()
    val locationEvents = MutableSharedFlow<Tracking>()
    val answered = mutableListOf<String>()

    override fun changes(): Flow<String?> = events

    override fun locations(): Flow<Tracking> = locationEvents

    override fun pendingArrival(orderId: String): ArrivalConfirmation? = arrivals.current(orderId)

    override fun arrivalChanges(): StateFlow<Int> = arrivals.version

    override fun arrivalAnswered(requestId: String) {
        answered += requestId
        arrivals.answered(requestId)
    }
}
