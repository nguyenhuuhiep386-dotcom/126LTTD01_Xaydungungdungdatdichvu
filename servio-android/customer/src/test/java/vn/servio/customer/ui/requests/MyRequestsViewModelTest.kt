package vn.servio.customer.ui.requests

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import vn.servio.core.data.model.Category
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.repository.ServiceRequestRepository
import vn.servio.customer.ui.requests.MyRequestsViewModel.Tab

@OptIn(ExperimentalCoroutinesApi::class)
class MyRequestsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Answers from [rows] like the server: filter by status, then cut the page. Records every call. */
    private class Server(var rows: List<ServiceRequestSummary>) : ServiceRequestRepository {
        val calls = mutableListOf<Pair<Set<ServiceRequestStatus>, Int>>()
        var failure: ApiResult.Failure? = null

        var categoryFailure: ApiResult.Failure? = null
        var categoryCalls = 0
        val categories = object : CategoryRepository {
            override suspend fun getCategoryTree(): ApiResult<List<Category>> {
                categoryCalls++
                return categoryFailure ?: ApiResult.Success(
                    listOf(
                        Category(id = "g1", name = "Điện – Nước", slug = "dien-nuoc", children = listOf(Category(id = "c1", parentId = "g1", name = "Sửa điện dân dụng", slug = "sua-dien"))),
                    ),
                )
            }
        }

        override suspend fun getMyRequests(
            statuses: Set<ServiceRequestStatus>,
            page: Int,
            pageSize: Int,
        ): ApiResult<PagedResult<ServiceRequestSummary>> {
            calls += statuses to page
            failure?.let { return it }
            val matching = rows.filter { it.status in statuses }
            val items = matching.drop((page - 1) * pageSize).take(pageSize)
            return ApiResult.Success(PagedResult(items, matching.size, page, pageSize, (matching.size + pageSize - 1) / pageSize))
        }

        // CS-15 calls: not used by CS-14.
        override suspend fun getDetail(requestId: String) = error("unused")
        override suspend fun cancel(requestId: String, reason: String?) = error("unused")
        override suspend fun getQuotes(requestId: String, sort: QuoteSort) = error("unused")
    }

    private fun row(id: Int, status: ServiceRequestStatus, quotes: Int = 0, categoryId: String = "c1") = ServiceRequestSummary(
        id = id.toString(), code = "SR$id", title = "Yêu cầu $id", categoryId = categoryId, categoryName = "Sửa điện dân dụng",
        status = status, quoteCount = quotes, addressSnapshot = "Đà Nẵng", createdAt = "2026-11-10T08:00:00+07:00",
    )

    private fun viewModelWith(server: Server) = MyRequestsViewModel(server, server.categories)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun tabsAskTheServerForTheirOwnStatuses() {
        assertEquals(setOf(ServiceRequestStatus.OPEN, ServiceRequestStatus.DRAFT), Tab.OPEN.statuses)
        assertEquals(setOf(ServiceRequestStatus.MATCHED), Tab.MATCHED.statuses)
        assertEquals(
            setOf(ServiceRequestStatus.EXPIRED, ServiceRequestStatus.CANCELLED, ServiceRequestStatus.REJECTED_BY_MODERATION),
            Tab.HISTORY.statuses,
        )
        // Every status belongs to exactly one tab.
        assertEquals(ServiceRequestStatus.entries.size, Tab.entries.sumOf { it.statuses.size })
        assertEquals(ServiceRequestStatus.entries.toSet(), Tab.entries.flatMap { it.statuses }.toSet())
    }

    @Test
    fun refreshLoadsOnlyTheOpenTabWhichCarriesTheNumber() = runTest(dispatcher) {
        val server = Server(
            listOf(
                row(1, ServiceRequestStatus.OPEN, quotes = 2),
                row(2, ServiceRequestStatus.DRAFT),
                row(3, ServiceRequestStatus.MATCHED),
                row(4, ServiceRequestStatus.CANCELLED),
            ),
        )
        val viewModel = viewModelWith(server)
        assertTrue(viewModel.state.value.current.loading)
        assertNull(viewModel.state.value.current.totalCount)

        viewModel.refresh()
        advanceUntilIdle()

        val tabs = viewModel.state.value.tabs
        assertEquals(listOf("1", "2"), tabs.getValue(Tab.OPEN).items.map { it.id })
        assertEquals(2, tabs.getValue(Tab.OPEN).totalCount)
        assertFalse(tabs.getValue(Tab.OPEN).loading)
        // No call for the numbers of "Đã ghép" and "Lịch sử".
        assertEquals(listOf(Tab.OPEN.statuses to 1), server.calls)
        assertFalse(tabs.getValue(Tab.MATCHED).fetched)
        assertNull(tabs.getValue(Tab.HISTORY).totalCount)
    }

    @Test
    fun anotherTabLoadsWhenOpenedAndOnlyOnce() = runTest(dispatcher) {
        val server = Server(listOf(row(1, ServiceRequestStatus.OPEN), row(3, ServiceRequestStatus.MATCHED)))
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.selectTab(Tab.MATCHED)
        assertTrue(viewModel.state.value.current.loading)
        advanceUntilIdle()
        assertEquals(listOf("3"), viewModel.state.value.current.items.map { it.id })

        viewModel.selectTab(Tab.OPEN)
        viewModel.selectTab(Tab.MATCHED)
        advanceUntilIdle()
        assertEquals(listOf(Tab.OPEN.statuses to 1, Tab.MATCHED.statuses to 1), server.calls)
    }

    @Test
    fun refreshOnAnotherTabReloadsItAndTheOpenNumberOnly() = runTest(dispatcher) {
        val server = Server(listOf(row(1, ServiceRequestStatus.OPEN), row(3, ServiceRequestStatus.MATCHED), row(4, ServiceRequestStatus.EXPIRED)))
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectTab(Tab.HISTORY)
        viewModel.selectTab(Tab.MATCHED)
        advanceUntilIdle()

        server.calls.clear()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(setOf(Tab.OPEN.statuses to 1, Tab.MATCHED.statuses to 1), server.calls.toSet())
        assertEquals(2, server.calls.size)
        // "Lịch sử" is stale now: it loads again when opened.
        assertFalse(viewModel.state.value.tabs.getValue(Tab.HISTORY).fetched)
        viewModel.selectTab(Tab.HISTORY)
        advanceUntilIdle()
        assertEquals(listOf("4"), viewModel.state.value.current.items.map { it.id })
    }

    @Test
    fun emptyTabIsContentWithZero() = runTest(dispatcher) {
        val viewModel = viewModelWith(Server(listOf(row(1, ServiceRequestStatus.OPEN))))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.selectTab(Tab.MATCHED)
        advanceUntilIdle()
        val current = viewModel.state.value.current
        assertTrue(current.items.isEmpty())
        assertEquals(0, current.totalCount)
        assertNull(current.error)
        assertFalse(current.loading)
    }

    @Test
    fun failureShowsTheMessageAndRetryRecovers() = runTest(dispatcher) {
        val server = Server(listOf(row(1, ServiceRequestStatus.OPEN)))
        server.failure = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()

        val failed = viewModel.state.value.current
        assertEquals("Không có kết nối mạng", failed.error)
        assertNull(failed.totalCount)
        assertTrue(failed.items.isEmpty())

        server.failure = null
        viewModel.refresh()
        assertTrue(viewModel.state.value.current.loading)
        assertNull(viewModel.state.value.current.error)
        advanceUntilIdle()

        assertNull(viewModel.state.value.current.error)
        assertEquals(1, viewModel.state.value.current.totalCount)
    }

    @Test
    fun refreshKeepsTheListOnScreenWhileLoading() = runTest(dispatcher) {
        val viewModel = viewModelWith(Server(listOf(row(1, ServiceRequestStatus.OPEN))))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.refresh()
        assertTrue(viewModel.state.value.current.loading)
        assertEquals(1, viewModel.state.value.current.items.size)
    }

    @Test
    fun loadMoreAppendsPagesUntilTheEnd() = runTest(dispatcher) {
        val server = Server((1..45).map { row(it, ServiceRequestStatus.OPEN) })
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(20, viewModel.state.value.current.items.size)
        assertEquals(45, viewModel.state.value.current.totalCount)

        viewModel.loadMore()
        viewModel.loadMore() // ignored while the first one is running
        advanceUntilIdle()
        assertEquals(40, viewModel.state.value.current.items.size)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals((1..45).map { it.toString() }, viewModel.state.value.current.items.map { it.id })
        assertFalse(viewModel.state.value.current.hasMore)

        server.calls.clear()
        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun loadMoreDropsRowsAlreadyOnScreen() = runTest(dispatcher) {
        val server = Server((1..25).map { row(it, ServiceRequestStatus.OPEN) })
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()

        // A new request is posted: the server's page 2 now starts with a row that page 1 already showed.
        server.rows = listOf(row(99, ServiceRequestStatus.OPEN)) + server.rows
        viewModel.loadMore()
        advanceUntilIdle()

        val ids = viewModel.state.value.current.items.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(25, ids.size)
    }

    @Test
    fun loadMoreFailureKeepsTheListAndCanBeRetried() = runTest(dispatcher) {
        val server = Server((1..30).map { row(it, ServiceRequestStatus.OPEN) })
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()

        server.failure = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(20, viewModel.state.value.current.items.size)
        assertNull(viewModel.state.value.current.error)
        assertFalse(viewModel.state.value.current.loadingMore)

        server.failure = null
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(30, viewModel.state.value.current.items.size)
    }

    @Test
    fun cardsNameTheCategoryGroupFromTheTree() = runTest(dispatcher) {
        val server = Server(listOf(row(1, ServiceRequestStatus.OPEN), row(2, ServiceRequestStatus.OPEN, categoryId = "unknown")))
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("Điện – Nước", "Sửa điện dân dụng"), viewModel.state.value.cards.map { it.categoryLabel })
        // The tree is asked for once; refreshing again uses what is known.
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, server.categoryCalls)
    }

    @Test
    fun withoutTheTreeCardsKeepTheServiceName() = runTest(dispatcher) {
        val server = Server(listOf(row(1, ServiceRequestStatus.OPEN))).apply { categoryFailure = ApiResult.Failure("NETWORK_ERROR", "x") }
        val viewModel = viewModelWith(server)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("Sửa điện dân dụng"), viewModel.state.value.cards.map { it.categoryLabel })
        assertEquals(1, viewModel.state.value.current.totalCount)
    }
}
