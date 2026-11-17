package vn.servio.customer.ui.requestdetail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.servio.core.data.model.Category
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.model.QuotePartner
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.repository.PartnerRepository
import vn.servio.customer.data.repository.QuoteUpdates
import vn.servio.customer.data.repository.ServiceRequestRepository
import vn.servio.customer.ui.requestdetail.RequestDetailViewModel.Event

@OptIn(ExperimentalCoroutinesApi::class)
class RequestDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val notFound = ApiResult.Failure("NOT_FOUND", "Không tìm thấy yêu cầu")
    private val offline = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")

    /** Sorts like #42 and changes the request like #40. Each answer can be replaced by a failure. */
    private class Server : ServiceRequestRepository {
        var detail: ServiceRequestDetail? = detail(ServiceRequestStatus.OPEN)
        var quotes = listOf(
            quote("late-cheap", amount = 180_000, availableFrom = "2026-11-11T16:00:00+00:00", rating = null),
            quote("early-dear", amount = 250_000, availableFrom = "2026-11-11T15:30:00+00:00", rating = 4.8),
        )
        var detailFailure: ApiResult.Failure? = null
        var quotesFailure: ApiResult.Failure? = null
        var cancelFailure: ApiResult.Failure? = null
        val quoteCalls = mutableListOf<QuoteSort>()
        val cancelReasons = mutableListOf<String?>()
        var detailCalls = 0

        var partnerFailure: ApiResult.Failure? = null
        val partnerCalls = mutableListOf<String>()
        val partners = object : PartnerRepository {
            override suspend fun getPublicProfile(partnerId: String): ApiResult<PartnerPublic> {
                partnerCalls += partnerId
                return partnerFailure ?: ApiResult.Success(PartnerPublic(id = partnerId, fullName = "Thợ", skills = listOf("Sửa điện dân dụng", "Sửa ống nước")))
            }

            override suspend fun getReviews(partnerId: String, page: Int, pageSize: Int) = error("unused")
        }

        var categoryFailure: ApiResult.Failure? = null
        val categories = object : CategoryRepository {
            override suspend fun getCategoryTree(): ApiResult<List<Category>> = categoryFailure ?: ApiResult.Success(
                listOf(
                    Category(id = "g1", name = "Điện lạnh", slug = "dien-lanh", children = listOf(Category(id = "c9", parentId = "g1", name = "Sửa máy lạnh", slug = "sua-may-lanh"))),
                    Category(id = "g2", name = "Điện – Nước", slug = "dien-nuoc", children = listOf(Category(id = "c1", parentId = "g2", name = "Sửa điện dân dụng", slug = "sua-dien"))),
                ),
            )
        }

        override suspend fun getDetail(requestId: String): ApiResult<ServiceRequestDetail> {
            detailCalls++
            return detailFailure ?: ApiResult.Success(detail!!)
        }

        /** Live "quotes changed" signals of r1 (NewQuote, QuoteWithdrawn, reconnect), already filtered. */
        val hub = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        val updates = object : QuoteUpdates {
            override fun changes(requestId: String) = hub
        }

        /** When set, #42 waits for it (a reload still running). */
        var quotesGate: CompletableDeferred<Unit>? = null
        private var quotesInFlight = 0
        var maxQuotesInFlight = 0

        override suspend fun getQuotes(requestId: String, sort: QuoteSort): ApiResult<PagedResult<CustomerQuote>> {
            quoteCalls += sort
            quotesInFlight++
            maxQuotesInFlight = maxOf(maxQuotesInFlight, quotesInFlight)
            try {
                quotesGate?.await()
            } finally {
                quotesInFlight--
            }
            quotesFailure?.let { return it }
            val sorted = when (sort) {
                QuoteSort.TIME -> quotes.sortedBy { it.availableFrom }
                QuoteSort.PRICE -> quotes.sortedBy { it.amount }
                QuoteSort.RATING -> quotes.sortedByDescending { it.partner.averageRating ?: -1.0 }
            }
            return ApiResult.Success(PagedResult(sorted, sorted.size, 1, 50, 1))
        }

        override suspend fun cancel(requestId: String, reason: String?): ApiResult<Unit> {
            cancelReasons += reason
            cancelFailure?.let { return it }
            detail = detail(ServiceRequestStatus.CANCELLED)
            quotes = emptyList()
            return ApiResult.Success(Unit)
        }

        override suspend fun getMyRequests(statuses: Set<ServiceRequestStatus>, page: Int, pageSize: Int):
            ApiResult<PagedResult<ServiceRequestSummary>> = error("unused")
    }

    private fun TestScope.viewModelWith(server: Server, events: MutableList<Event> = mutableListOf()): RequestDetailViewModel {
        val viewModel = RequestDetailViewModel(SavedStateHandle(mapOf("requestId" to "r1")), server, server.partners, server.categories, server.updates)
        // Unconfined: an event is recorded as soon as it is sent (advanceUntilIdle does not wait for background work).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        return viewModel
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadShowsTheRequestAndItsQuotesByTime() = runTest(dispatcher) {
        val server = Server()
        val viewModel = viewModelWith(server)
        assertEquals("r1", viewModel.requestId)
        assertTrue(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.hasContent)

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals("SR1", state.detail?.code)
        assertEquals(listOf("early-dear", "late-cheap"), state.quotes?.map { it.id })
        assertEquals(listOf(QuoteSort.TIME), server.quoteCalls)
    }

    @Test
    fun firstLoadFailureIsAFullScreenError() = runTest(dispatcher) {
        val server = Server().apply { detailFailure = notFound }
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        viewModel.load()
        advanceUntilIdle()

        assertEquals("Không tìm thấy yêu cầu", viewModel.state.value.error)
        assertFalse(viewModel.state.value.hasContent)
        assertTrue(events.isEmpty())

        // Retry.
        server.detailFailure = null
        viewModel.load()
        advanceUntilIdle()
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.hasContent)
    }

    @Test
    fun quotesFailureOnFirstLoadIsAlsoAFullScreenError() = runTest(dispatcher) {
        val server = Server().apply { quotesFailure = offline }
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()

        assertEquals(offline.message, viewModel.state.value.error)
        assertFalse(viewModel.state.value.hasContent)
    }

    @Test
    fun failedReloadKeepsTheContentAndSendsAMessage() = runTest(dispatcher) {
        val server = Server()
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        viewModel.load()
        advanceUntilIdle()

        server.detailFailure = offline
        server.quotesFailure = offline
        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.error)
        assertFalse(state.loading)
        assertEquals("SR1", state.detail?.code)
        assertEquals(2, state.quotes?.size)
        assertEquals(listOf<Event>(Event.Message(offline.message)), events)
    }

    @Test
    fun sortAsksTheServerAndKeepsTheOldOrderOnFailure() = runTest(dispatcher) {
        val server = Server()
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        viewModel.load()
        advanceUntilIdle()

        viewModel.setSort(QuoteSort.PRICE)
        advanceUntilIdle()
        assertEquals(QuoteSort.PRICE, viewModel.state.value.sort)
        assertEquals(listOf("late-cheap", "early-dear"), viewModel.state.value.quotes?.map { it.id })
        assertEquals(1, server.detailCalls)

        viewModel.setSort(QuoteSort.PRICE) // same order: no call
        advanceUntilIdle()
        assertEquals(listOf(QuoteSort.TIME, QuoteSort.PRICE), server.quoteCalls)

        server.quotesFailure = offline
        viewModel.setSort(QuoteSort.RATING)
        advanceUntilIdle()
        assertEquals(QuoteSort.PRICE, viewModel.state.value.sort)
        assertEquals(listOf("late-cheap", "early-dear"), viewModel.state.value.quotes?.map { it.id })
        assertEquals(listOf<Event>(Event.Message(offline.message)), events)

        // The next full load keeps the order the list is in.
        server.quotesFailure = null
        viewModel.load()
        advanceUntilIdle()
        assertEquals(QuoteSort.PRICE, server.quoteCalls.last())
    }

    @Test
    fun reloadQuotesPicksUpNewAndWithdrawnQuotes() = runTest(dispatcher) {
        val server = Server()
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()

        server.quotes = server.quotes.filter { it.id != "late-cheap" } +
            quote("new", amount = 200_000, availableFrom = "2026-11-11T15:00:00+00:00", rating = null)
        viewModel.reloadQuotes()
        advanceUntilIdle()

        assertEquals(listOf("new", "early-dear"), viewModel.state.value.quotes?.map { it.id })
        assertEquals(1, server.detailCalls)
    }

    @Test
    fun cancelSendsTheReasonAndReloads() = runTest(dispatcher) {
        val server = Server()
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.detail!!.canCancel)

        viewModel.cancel("Da tu sua duoc")
        viewModel.cancel("bam hai lan") // ignored while the first one is running
        assertTrue(viewModel.state.value.cancelling)
        advanceUntilIdle()

        assertEquals(listOf<String?>("Da tu sua duoc"), server.cancelReasons)
        assertEquals(listOf<Event>(Event.Cancelled), events)
        val state = viewModel.state.value
        assertFalse(state.cancelling)
        assertEquals(ServiceRequestStatus.CANCELLED, state.detail?.status)
        assertFalse(state.detail!!.canCancel)
        assertTrue(state.quotes!!.isEmpty())
    }

    @Test
    fun cancelConflictShowsTheMessageAndReloadsTheStatus() = runTest(dispatcher) {
        val server = Server()
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        viewModel.load()
        advanceUntilIdle()

        // The request expired on the server in the meantime.
        server.detail = detail(ServiceRequestStatus.EXPIRED)
        server.cancelFailure = ApiResult.Failure("INVALID_STATUS_TRANSITION", "Trạng thái đơn đã thay đổi, vui lòng tải lại.")
        viewModel.cancel(null)
        advanceUntilIdle()

        assertEquals(listOf<Event>(Event.CancelConflict), events)
        assertEquals(ServiceRequestStatus.EXPIRED, viewModel.state.value.detail?.status)
        assertFalse(viewModel.state.value.cancelling)
    }

    @Test
    fun otherCancelErrorsKeepTheServerMessage() = runTest(dispatcher) {
        val server = Server().apply { cancelFailure = offline }
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        viewModel.load()
        advanceUntilIdle()

        viewModel.cancel(null)
        advanceUntilIdle()

        assertEquals(listOf<Event>(Event.Message(offline.message)), events)
        assertEquals(ServiceRequestStatus.OPEN, viewModel.state.value.detail?.status)
    }

    @Test
    fun onlyPendingQuotesOfAnOpenRequestCanBeSelected() = runTest(dispatcher) {
        val server = Server()
        val pending = server.quotes.first()
        val accepted = pending.copy(id = "accepted", status = QuoteStatus.ACCEPTED)
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canSelect(pending))
        assertFalse(viewModel.state.value.canSelect(accepted))

        server.detail = detail(ServiceRequestStatus.MATCHED)
        viewModel.load()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.canSelect(pending))
    }

    @Test
    fun quoteGoneIsReportedOnce() = runTest(dispatcher) {
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(Server(), events)
        viewModel.onQuoteGone()
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.QuoteGone), events)
    }

    @Test
    fun categoryGroupComesFromTheCategoryTree() = runTest(dispatcher) {
        val server = Server().apply { categoryFailure = offline }
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()
        // The tree failed: the request is shown anyway, without its group.
        assertTrue(viewModel.state.value.hasContent)
        assertNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.categoryGroup)

        server.categoryFailure = null
        viewModel.load()
        advanceUntilIdle()
        assertEquals("Điện – Nước", viewModel.state.value.categoryGroup)
    }

    @Test
    fun partnerSkillsAreLoadedOncePerPartnerAndRetriedAfterAFailure() = runTest(dispatcher) {
        val server = Server().apply { partnerFailure = notFound }
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)

        viewModel.loadPartnerSkills("p1")
        viewModel.loadPartnerSkills("p1") // already running
        advanceUntilIdle()
        // Failed: no chips, no message; the sheet is not blocked.
        assertNull(viewModel.state.value.partnerSkills["p1"])
        assertTrue(events.isEmpty())
        assertEquals(listOf("p1"), server.partnerCalls)

        server.partnerFailure = null
        viewModel.loadPartnerSkills("p1")
        advanceUntilIdle()
        assertEquals(listOf("Sửa điện dân dụng", "Sửa ống nước"), viewModel.state.value.partnerSkills["p1"])

        viewModel.loadPartnerSkills("p1") // known: no call
        advanceUntilIdle()
        assertEquals(listOf("p1", "p1"), server.partnerCalls)
    }

    // region Live quotes (NewQuote / QuoteWithdrawn / reconnect)

    @Test
    fun liveEventsWithinTheWindowGiveOneReload() = runTest(dispatcher) {
        val server = Server()
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()
        server.quotes = server.quotes + quote("new", amount = 200_000, availableFrom = "2026-11-11T15:45:00+00:00", rating = null)

        repeat(3) {
            server.hub.tryEmit(Unit)
            advanceTimeBy(100)
        }
        advanceTimeBy(RequestDetailViewModel.LIVE_RELOAD_WINDOW_MILLIS)
        runCurrent()

        assertEquals(2, server.quoteCalls.size) // first load + one live reload
        assertEquals(3, viewModel.state.value.quotes?.size)
        assertFalse(viewModel.state.value.loading) // silent: no spinner
    }

    @Test
    fun liveReloadWaitsForTheWindow() = runTest(dispatcher) {
        val server = Server()
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()

        server.hub.tryEmit(Unit)
        advanceTimeBy(RequestDetailViewModel.LIVE_RELOAD_WINDOW_MILLIS - 1)
        runCurrent()
        assertEquals(1, server.quoteCalls.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, server.quoteCalls.size)
    }

    @Test
    fun eventsDuringAReloadGiveExactlyOneMoreAfterIt() = runTest(dispatcher) {
        val server = Server()
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        server.quotesGate = gate
        server.hub.tryEmit(Unit)
        advanceTimeBy(RequestDetailViewModel.LIVE_RELOAD_WINDOW_MILLIS + 1)
        runCurrent()
        assertEquals(2, server.quoteCalls.size) // live reload running, held by the gate

        repeat(4) {
            server.hub.tryEmit(Unit)
            advanceTimeBy(1_000)
        }
        assertEquals(2, server.quoteCalls.size) // nothing on top of the running call

        server.quotesGate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, server.quoteCalls.size) // exactly one more
        assertEquals(1, server.maxQuotesInFlight)
    }

    @Test
    fun liveReloadKeepsTheChosenSort() = runTest(dispatcher) {
        val server = Server()
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()
        viewModel.setSort(QuoteSort.PRICE)
        advanceUntilIdle()

        server.hub.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(QuoteSort.PRICE, server.quoteCalls.last())
        assertEquals(QuoteSort.PRICE, viewModel.state.value.sort)
        assertEquals(listOf("late-cheap", "early-dear"), viewModel.state.value.quotes?.map { it.id })
    }

    @Test
    fun aWithdrawnQuoteLeavesTheListAndAFailedLiveReloadIsSilent() = runTest(dispatcher) {
        val server = Server()
        val events = mutableListOf<Event>()
        val viewModel = viewModelWith(server, events)
        viewModel.load()
        advanceUntilIdle()

        server.quotes = server.quotes.filter { it.id != "early-dear" }
        server.hub.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(listOf("late-cheap"), viewModel.state.value.quotes?.map { it.id })

        server.quotesFailure = offline
        server.hub.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(listOf("late-cheap"), viewModel.state.value.quotes?.map { it.id })
        assertTrue(events.isEmpty())
    }

    @Test
    fun liveEventBeforeAnyContentRunsTheFullLoad() = runTest(dispatcher) {
        val server = Server().apply { detailFailure = offline }
        val viewModel = viewModelWith(server)
        viewModel.load()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.hasContent)

        server.detailFailure = null
        server.hub.tryEmit(Unit) // e.g. the hub reconnected after the network came back
        advanceUntilIdle()

        assertTrue(viewModel.state.value.hasContent)
        assertNull(viewModel.state.value.error)
    }

    // endregion

    private companion object {
        fun detail(status: ServiceRequestStatus) = ServiceRequestDetail(
            id = "r1", code = "SR1", status = status, revision = 1, categoryId = "c1", categoryName = "Sửa điện dân dụng",
            title = "Ổ điện bị hỏng", description = "Ổ điện phòng khách bị lỏng", addressSnapshot = "Đà Nẵng",
            scheduleType = ScheduleType.NOW, createdAt = "2026-11-11T14:50:00+00:00",
        )

        fun quote(id: String, amount: Long, availableFrom: String, rating: Double?) = CustomerQuote(
            id = id, serviceRequestId = "r1", amount = amount, estimatedDurationMinutes = 60,
            availableFrom = availableFrom, estimatedEndAt = availableFrom, status = QuoteStatus.PENDING, rowVersion = "AAAA",
            partner = QuotePartner(id = "p-$id", fullName = "Thợ $id", averageRating = rating, totalReviews = if (rating == null) 0 else 3),
            createdAt = "2026-11-11T15:00:00+00:00",
        )
    }
}
