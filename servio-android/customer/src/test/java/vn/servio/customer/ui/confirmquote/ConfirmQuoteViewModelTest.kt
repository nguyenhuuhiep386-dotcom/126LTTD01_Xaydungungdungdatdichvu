package vn.servio.customer.ui.confirmquote

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.AcceptQuotesBody
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.CancelResult
import vn.servio.customer.data.model.CreatedOrder
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderListFilter
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuotePartner
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.ServiceRequestRepository
import vn.servio.customer.ui.confirmquote.ConfirmFailure.DialogKind
import vn.servio.customer.ui.confirmquote.ConfirmQuoteViewModel.Event

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmQuoteViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val offline = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")

    /** #38, #42 and #43 of one request "r1" with the quote "q1" (and "q2"). */
    private class Server : ServiceRequestRepository, OrderRepository {
        var detail = detail(ServiceRequestStatus.OPEN)
        var quotes = listOf(quote("q1", 250_000, "v1"), quote("q2", 180_000, "v1"))
        var loadFailure: ApiResult.Failure? = null
        val accepted = mutableListOf<Triple<String, String, String>>()
        var acceptAnswer: ApiResult<CreatedOrder> = ApiResult.Success(CreatedOrder("o1", "OD1"))
        /** When set, #43 waits for it (to tap again while a call is running). */
        var acceptGate: CompletableDeferred<Unit>? = null
        /** Applied when #43 answers (e.g. the partner changed the price before). */
        var onAccept: () -> Unit = {}
        var loads = 0

        override suspend fun getDetail(requestId: String): ApiResult<ServiceRequestDetail> {
            loads++
            return loadFailure ?: ApiResult.Success(detail)
        }

        override suspend fun getQuotes(requestId: String, sort: QuoteSort): ApiResult<PagedResult<CustomerQuote>> =
            loadFailure ?: ApiResult.Success(PagedResult(quotes, quotes.size, 1, 50, 1))

        override suspend fun acceptQuote(requestId: String, quoteId: String, rowVersion: String): ApiResult<CreatedOrder> {
            accepted += Triple(requestId, quoteId, rowVersion)
            acceptGate?.await()
            onAccept()
            return acceptAnswer
        }

        override suspend fun cancel(requestId: String, reason: String?): ApiResult<Unit> = error("unused")

        override suspend fun getMyRequests(statuses: Set<ServiceRequestStatus>, page: Int, pageSize: Int):
            ApiResult<PagedResult<ServiceRequestSummary>> = error("unused")

        override suspend fun getMyOrders(filter: OrderListFilter, page: Int, pageSize: Int): ApiResult<PagedResult<OrderSummary>> = error("unused")

        override suspend fun getOrder(orderId: String): ApiResult<OrderDetail> = error("unused")

        override suspend fun cancelPreview(orderId: String): ApiResult<CancelPreview> = error("unused")

        override suspend fun cancel(orderId: String, reason: String, rowVersion: String): ApiResult<CancelResult> = error("unused")

        override suspend fun cancelDebtLimit(): ApiResult<Long?> = error("unused")

        override suspend fun respondExtraCharge(orderId: String, extraChargeId: String, approved: Boolean): ApiResult<vn.servio.customer.data.model.ExtraCharge> = error("unused")

        override suspend fun getTimeline(orderId: String): ApiResult<List<vn.servio.customer.data.model.TimelineEntry>> = error("unused")

        override suspend fun confirmCompletion(orderId: String, rowVersion: String): ApiResult<vn.servio.customer.data.model.ConfirmCompletionResult> = error("unused")

        override suspend fun getTracking(orderId: String, assignmentId: String): ApiResult<vn.servio.customer.data.model.Tracking?> = error("unused")

        override suspend fun respondArrival(
            confirmation: vn.servio.customer.data.model.ArrivalConfirmation,
            confirmed: Boolean,
        ): ApiResult<vn.servio.customer.data.model.ArrivalConfirmation> = error("unused")
    }

    private fun TestScope.viewModelWith(server: Server, events: MutableList<Event> = mutableListOf(), quoteId: String = "q1"): ConfirmQuoteViewModel {
        val viewModel = ConfirmQuoteViewModel(SavedStateHandle(mapOf("requestId" to "r1", "quoteId" to quoteId)), server, server)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        return viewModel
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun opensWithTheChosenQuoteOfAnOpenRequest() = runTest(dispatcher) {
        val viewModel = viewModelWith(Server())
        assertTrue(viewModel.state.value.loading)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.available)
        assertFalse(state.unavailable)
        assertEquals("q1", state.quote?.id)
        assertEquals(250_000L, state.quote?.amount)
    }

    @Test
    fun aQuoteNoLongerListedOrNotPendingOrAClosedRequestIsUnavailable() = runTest(dispatcher) {
        val gone = Server().apply { quotes = listOf(quote("q2", 180_000, "v1")) }
        assertTrue(viewModelWith(gone).also { advanceUntilIdle() }.state.value.unavailable)

        val accepted = Server().apply { quotes = listOf(quote("q1", 250_000, "v1", QuoteStatus.ACCEPTED)) }
        assertTrue(viewModelWith(accepted).also { advanceUntilIdle() }.state.value.unavailable)

        val matched = Server().apply { detail = detail(ServiceRequestStatus.MATCHED) }
        assertTrue(viewModelWith(matched).also { advanceUntilIdle() }.state.value.unavailable)
    }

    @Test
    fun aFailedFirstLoadShowsTheErrorAndRetryLoadsAgain() = runTest(dispatcher) {
        val server = Server().apply { loadFailure = offline }
        val viewModel = viewModelWith(server)
        advanceUntilIdle()
        assertEquals(offline.message, viewModel.state.value.error)
        assertFalse(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.unavailable)

        server.loadFailure = null
        viewModel.load()
        advanceUntilIdle()
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.available)
    }

    @Test
    fun confirmSendsTheQuoteWithItsRowVersionAndOpensTheOrder() = runTest(dispatcher) {
        val server = Server()
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()

        assertEquals(listOf(Triple("r1", "q1", "v1")), server.accepted)
        assertEquals(listOf<Event>(Event.OrderCreated(CreatedOrder("o1", "OD1"))), events)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun aSecondTapWhileSendingIsIgnored() = runTest(dispatcher) {
        val server = Server().apply { acceptGate = CompletableDeferred() }
        val viewModel = viewModelWith(server)
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.submitting)
        viewModel.confirm()
        server.acceptGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, server.accepted.size)
    }

    @Test
    fun aChangedQuoteIsReloadedAndConfirmedAgainWithTheNewVersion() = runTest(dispatcher) {
        val server = Server().apply {
            acceptAnswer = ApiResult.Failure("RESOURCE_VERSION_CONFLICT", "Báo giá đã thay đổi")
            onAccept = { quotes = listOf(quote("q1", 300_000, "v2")) }
        }
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(DialogKind.CHANGED, state.dialog?.kind)
        assertEquals(300_000L, state.quote?.amount)
        assertTrue(state.available)
        assertFalse(state.submitting)

        viewModel.onDialogClosed()
        server.acceptAnswer = ApiResult.Success(CreatedOrder("o1", "OD1"))
        server.onAccept = {}
        viewModel.confirm()
        advanceUntilIdle()
        assertEquals("v2", server.accepted.last().third)
        assertEquals(Event.OrderCreated(CreatedOrder("o1", "OD1")), events.last())
    }

    @Test
    fun aWithdrawnQuoteTurnsTheScreenUnavailableWithoutADialog() = runTest(dispatcher) {
        val server = Server().apply {
            acceptAnswer = ApiResult.Failure("RESOURCE_VERSION_CONFLICT", "Báo giá đã thay đổi")
            onAccept = { quotes = listOf(quote("q2", 180_000, "v1")) }
        }
        val viewModel = viewModelWith(server)
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.unavailable)
        assertNull(viewModel.state.value.dialog)
    }

    @Test
    fun errorsThatEndTheBookingShowTheirDialog() = runTest(dispatcher) {
        val server = Server().apply { acceptAnswer = ApiResult.Failure("SCHEDULE_CONFLICT", "Đối tác đã có lịch trùng") }
        val viewModel = viewModelWith(server)
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()
        assertEquals(ConfirmFailure.Dialog(DialogKind.PARTNER_UNAVAILABLE, "Đối tác đã có lịch trùng"), viewModel.state.value.dialog)

        viewModel.onDialogClosed()
        assertNull(viewModel.state.value.dialog)
    }

    @Test
    fun aNetworkErrorOffersToTryAgain() = runTest(dispatcher) {
        val server = Server().apply { acceptAnswer = offline }
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.Retry(offline.message)), events)
        assertFalse(viewModel.state.value.submitting)
        assertNull(viewModel.state.value.dialog)
        assertTrue(viewModel.state.value.available)
    }

    @Test
    fun nothingIsSentForAnUnavailableQuote() = runTest(dispatcher) {
        val server = Server().apply { detail = detail(ServiceRequestStatus.EXPIRED) }
        val viewModel = viewModelWith(server)
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()
        assertTrue(server.accepted.isEmpty())
    }

    @Test
    fun bodyOfOneCashQuote() {
        assertEquals(
            AcceptQuotesBody(listOf("q1"), mapOf("q1" to "v1"), "CASH"),
            AcceptQuotesBody.one("q1", "v1"),
        )
    }

    private companion object {
        fun detail(status: ServiceRequestStatus) = ServiceRequestDetail(
            id = "r1", code = "SR1", status = status, revision = 1, categoryId = "c1", categoryName = "Sửa điện dân dụng",
            title = "Ổ điện bị hỏng", description = "Ổ điện phòng khách bị lỏng", addressSnapshot = "Đà Nẵng",
            scheduleType = ScheduleType.NOW, createdAt = "2026-11-11T14:50:00+00:00",
        )

        fun quote(id: String, amount: Long, rowVersion: String, status: QuoteStatus = QuoteStatus.PENDING) = CustomerQuote(
            id = id, serviceRequestId = "r1", amount = amount, estimatedDurationMinutes = 60,
            availableFrom = "2026-11-17T14:00:00+00:00", estimatedEndAt = "2026-11-17T15:00:00+00:00", status = status,
            rowVersion = rowVersion, partner = QuotePartner(id = "p-$id", fullName = "Thợ $id"),
            createdAt = "2026-11-17T13:00:00+00:00",
        )
    }
}
