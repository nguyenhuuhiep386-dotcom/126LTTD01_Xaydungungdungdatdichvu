package vn.servio.customer.ui.partner

import androidx.lifecycle.SavedStateHandle
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
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.model.PartnerReview
import vn.servio.customer.data.model.QuotePartner
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.repository.PartnerRepository
import vn.servio.customer.data.repository.ServiceRequestRepository

@OptIn(ExperimentalCoroutinesApi::class)
class PartnerProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val offline = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
    private val notFound = ApiResult.Failure("NOT_FOUND", "Không tìm thấy đối tác")

    /** #27, #28 of partner "p1"; #38, #42 of request "r1" with quote "q1" by "p1". */
    private class Server : PartnerRepository, ServiceRequestRepository {
        var profile: ApiResult<PartnerPublic> = ApiResult.Success(PartnerPublic(id = "p1", fullName = "Phạm Văn Hùng", averageRating = 4.5, totalReviews = 2))
        var reviews: ApiResult<PagedResult<PartnerReview>> = ApiResult.Success(page(review("a", 5), review("b", 4)))
        var requestStatus = ServiceRequestStatus.OPEN
        var quoteStatus = QuoteStatus.PENDING
        var quotePartner = "p1"
        var requestFailure: ApiResult.Failure? = null
        val reviewCalls = mutableListOf<Pair<Int, Int>>()
        var requestCalls = 0

        override suspend fun getPublicProfile(partnerId: String) = profile

        override suspend fun getReviews(partnerId: String, page: Int, pageSize: Int): ApiResult<PagedResult<PartnerReview>> {
            reviewCalls += page to pageSize
            return reviews
        }

        override suspend fun getDetail(requestId: String): ApiResult<ServiceRequestDetail> {
            requestCalls++
            return requestFailure ?: ApiResult.Success(
                ServiceRequestDetail(
                    id = "r1", code = "SR2611180028", status = requestStatus, revision = 1, categoryName = "Sửa tủ lạnh",
                    title = "Tủ lạnh không đông đá", description = "", addressSnapshot = "", scheduleType = ScheduleType.SCHEDULED,
                    createdAt = "2026-11-18T01:09:45+07:00",
                ),
            )
        }

        override suspend fun getQuotes(requestId: String, sort: QuoteSort): ApiResult<PagedResult<CustomerQuote>> {
            val quote = CustomerQuote(
                id = "q1", serviceRequestId = "r1", amount = 400_000, estimatedDurationMinutes = 90,
                availableFrom = "2026-11-19T09:00:00+07:00", estimatedEndAt = "2026-11-19T10:30:00+07:00",
                status = quoteStatus, rowVersion = "v1", partner = QuotePartner(id = quotePartner, fullName = "Phạm Văn Hùng"),
                createdAt = "2026-11-18T01:09:45+07:00",
            )
            return requestFailure ?: ApiResult.Success(PagedResult(listOf(quote), 1, 1, 50, 1))
        }

        override suspend fun cancel(requestId: String, reason: String?): ApiResult<Unit> = error("unused")

        override suspend fun getMyRequests(statuses: Set<ServiceRequestStatus>, page: Int, pageSize: Int):
            ApiResult<PagedResult<ServiceRequestSummary>> = error("unused")

        companion object {
            fun review(id: String, rating: Int) =
                PartnerReview(id = id, rating = rating, comment = "Tốt", reviewerName = "Lan N.", createdAt = "2026-11-18T01:09:43+07:00")

            fun page(vararg items: PartnerReview) = PagedResult(items.toList(), items.size, 1, 10, 1)
        }
    }

    private fun viewModel(server: Server, requestId: String? = null, quoteId: String? = null, partnerId: String = "p1") =
        PartnerProfileViewModel(
            SavedStateHandle(mapOf("partnerId" to partnerId, "requestId" to requestId.orEmpty(), "quoteId" to quoteId.orEmpty())),
            server,
            server,
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the profile and one page of 10 reviews`() = runTest(dispatcher) {
        val server = Server()
        val vm = viewModel(server)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Phạm Văn Hùng", state.profile?.fullName)
        assertEquals(2, state.reviews?.size)
        assertEquals(listOf(1 to 10), server.reviewCalls)
        assertFalse(state.loading)
        assertFalse(state.reviewsLoading)
        assertNull(state.error)
        assertFalse(state.notFound)
    }

    @Test
    fun `without a quote no bar and no request call`() = runTest(dispatcher) {
        val server = Server()
        val vm = viewModel(server)
        advanceUntilIdle()

        assertNull(vm.state.value.booking)
        assertEquals(0, server.requestCalls)
    }

    @Test
    fun `pending quote of an open request shows the bar with code and amount`() = runTest(dispatcher) {
        val vm = viewModel(Server(), requestId = "r1", quoteId = "q1")
        advanceUntilIdle()

        assertEquals(PartnerProfileViewModel.Booking("SR2611180028", 400_000), vm.state.value.booking)
    }

    @Test
    fun `no bar when the quote is accepted, the request matched, or another partner's`() = runTest(dispatcher) {
        val accepted = Server().apply { quoteStatus = QuoteStatus.ACCEPTED; requestStatus = ServiceRequestStatus.MATCHED }
        val other = Server().apply { quotePartner = "p2" }
        val expired = Server().apply { requestStatus = ServiceRequestStatus.EXPIRED }
        val vms = listOf(accepted, other, expired).map { viewModel(it, requestId = "r1", quoteId = "q1") }
        advanceUntilIdle()

        vms.forEach { assertNull(it.state.value.booking) }
        vms.forEach { assertEquals("Phạm Văn Hùng", it.state.value.profile?.fullName) }
    }

    @Test
    fun `quote gone from #42 or #38 failing hides the bar but not the profile`() = runTest(dispatcher) {
        val failing = Server().apply { requestFailure = offline }
        val gone = viewModel(Server(), requestId = "r1", quoteId = "q9")
        val failed = viewModel(failing, requestId = "r1", quoteId = "q1")
        advanceUntilIdle()

        assertNull(gone.state.value.booking)
        assertNull(failed.state.value.booking)
        assertEquals("p1", failed.state.value.profile?.id)
        assertNull(failed.state.value.error)
    }

    @Test
    fun `404 is one state for not found and not related`() = runTest(dispatcher) {
        val server = Server().apply { profile = notFound }
        val vm = viewModel(server, requestId = "r1", quoteId = "q1")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.notFound)
        assertNull(state.error)
        assertNull(state.profile)
        assertNull(state.booking)
        assertFalse(state.loading)
        assertFalse(state.reviewsLoading)
    }

    @Test
    fun `missing partnerId is treated as not found without any call`() = runTest(dispatcher) {
        val server = Server()
        val vm = viewModel(server, partnerId = "")
        advanceUntilIdle()

        assertTrue(vm.state.value.notFound)
        assertTrue(server.reviewCalls.isEmpty())
    }

    @Test
    fun `other profile failure shows retry and retry loads it`() = runTest(dispatcher) {
        val server = Server().apply { profile = offline }
        val vm = viewModel(server)
        advanceUntilIdle()
        assertEquals("Không có kết nối mạng", vm.state.value.error)
        assertFalse(vm.state.value.notFound)

        server.profile = ApiResult.Success(PartnerPublic(id = "p1", fullName = "Phạm Văn Hùng"))
        vm.load()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals("p1", vm.state.value.profile?.id)
        assertEquals(2, vm.state.value.reviews?.size)
    }

    @Test
    fun `reviews failing keeps the profile and can be retried alone`() = runTest(dispatcher) {
        val server = Server().apply { reviews = offline }
        val vm = viewModel(server)
        advanceUntilIdle()

        assertEquals("p1", vm.state.value.profile?.id)
        assertNull(vm.state.value.reviews)
        assertEquals("Không có kết nối mạng", vm.state.value.reviewsError)

        server.reviews = ApiResult.Success(Server.page())
        vm.loadReviews()
        advanceUntilIdle()

        assertEquals(emptyList<PartnerReview>(), vm.state.value.reviews)
        assertNull(vm.state.value.reviewsError)
        assertEquals(2, server.reviewCalls.size)
    }
}
