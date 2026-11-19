package vn.servio.customer.ui.orderdetail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderParty
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.model.PartnerReview
import vn.servio.customer.data.model.Tracking
import vn.servio.customer.data.model.TrackingLocation
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.data.repository.FakeOrderUpdates
import vn.servio.customer.data.repository.PartnerRepository

@OptIn(ExperimentalCoroutinesApi::class)
class OrderDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val orderId = "01a1eab3-5bdc-75d2-8704-e48663625f6a"

    private class Partners : PartnerRepository {
        var profile: ApiResult<PartnerPublic> = ApiResult.Success(PartnerPublic(id = "p1", fullName = "Thợ Một", averageRating = 4.8, totalReviews = 32))
        var calls = 0

        override suspend fun getPublicProfile(partnerId: String): ApiResult<PartnerPublic> {
            calls++
            return profile
        }

        override suspend fun getReviews(partnerId: String, page: Int, pageSize: Int): ApiResult<PagedResult<PartnerReview>> = error("unused")
    }

    private fun order(status: OrderStatus) = OrderDetail(
        id = orderId,
        code = "OD2611180010",
        status = status,
        assignments = listOf(OrderAssignment("a1", status, isPrimary = true, partner = OrderParty("p1", "Thợ Một", rating = 4.8))),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(server: FakeOrderRepository, partners: Partners = Partners(), updates: FakeOrderUpdates = FakeOrderUpdates()) =
        OrderDetailViewModel(SavedStateHandle(mapOf("orderId" to orderId)), server, partners, updates).also {
            it.load()
            advanceUntilIdle()
        }

    @Test
    fun `a cancellable order loads the review count and the cancel fee`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order(OrderStatus.ON_THE_WAY))
            previews += ApiResult.Success(CancelPreview(20_000, emptyList(), "v1", "2026-11-18T03:01:00Z"))
        }
        val state = viewModel(server).state.value
        assertEquals(OrderStatus.ON_THE_WAY, state.detail?.status)
        assertEquals(32, state.reviewCount)
        assertEquals(20_000L, state.cancelFee)
        assertFalse(state.loading)
    }

    @Test
    fun `a finished order does not ask 151 and a failed 27 leaves the count unknown`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.COMPLETED)) }
        val partners = Partners().apply { profile = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng") }
        val state = viewModel(server, partners).state.value
        assertEquals(0, server.previewCalls)
        assertNull(state.cancelFee)
        assertNull(state.reviewCount)
    }

    @Test
    fun `a failed 151 leaves the fee unknown so the label stays plain`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order(OrderStatus.ACCEPTED))
            previews += ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
        }
        val state = viewModel(server).state.value
        assertNull(state.cancelFee)
        assertFalse(OrderDetailUi.cancelIsFree(state.cancelFee))
    }

    @Test
    fun `404 and 403 show the not found screen`() = runTest(dispatcher) {
        listOf("NOT_FOUND", "FORBIDDEN").forEach { code ->
            val server = FakeOrderRepository().apply { order = ApiResult.Failure(code, "Không tìm thấy đơn") }
            val state = viewModel(server).state.value
            assertTrue(state.notFound)
            assertNull(state.error)
        }
    }

    @Test
    fun `a first network error asks to retry, a later one keeps the order`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng") }
        val vm = viewModel(server)
        assertEquals("Không có kết nối mạng", vm.state.value.error)

        server.order = ApiResult.Success(order(OrderStatus.COMPLETED))
        vm.load()
        advanceUntilIdle()
        server.order = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
        vm.load()
        advanceUntilIdle()
        assertEquals(OrderStatus.COMPLETED, vm.state.value.detail?.status)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `changes of this order reload it once, others are ignored`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.PENDING)); previews += ApiResult.Success(CancelPreview(0, emptyList(), "v1", "x")) }
        val updates = FakeOrderUpdates()
        val vm = viewModel(server, updates = updates)
        assertEquals(1, server.orderCalls)

        updates.events.emit("01a1eab3-0000-0000-0000-000000000000")
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, server.orderCalls)

        server.order = ApiResult.Success(order(OrderStatus.ACCEPTED))
        updates.events.emit(orderId.uppercase())
        updates.events.emit(null)
        advanceTimeBy(600)
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, server.orderCalls)
        assertEquals(OrderStatus.ACCEPTED, vm.state.value.detail?.status)
    }

    @Test
    fun `on the way asks 76 with each reload, then follows the fixes of its assignment`() = runTest(dispatcher) {
        val fix = { assignment: String, at: String ->
            Tracking(orderId, assignment, assignmentStatus = OrderStatus.ON_THE_WAY, location = TrackingLocation(10.85, 106.77, 10, at), distanceKm = 3.0, etaMinutes = 8)
        }
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order(OrderStatus.ON_THE_WAY))
            previews += ApiResult.Success(CancelPreview(20_000, emptyList(), "v1", "x"))
            tracking = ApiResult.Success(fix("a1", "2026-11-18T07:00:00Z"))
        }
        val updates = FakeOrderUpdates()
        val vm = viewModel(server, updates = updates)
        assertEquals(1, server.trackingCalls)
        assertEquals(8, vm.state.value.tracking?.etaMinutes)

        updates.locationEvents.emit(fix("other", "2026-11-18T07:00:30Z"))
        updates.locationEvents.emit(fix("A1", "2026-11-18T07:00:10Z"))
        runCurrent()
        assertEquals("2026-11-18T07:00:10Z", vm.state.value.tracking?.location?.capturedAt)

        updates.events.emit(null) // reconnect
        advanceTimeBy(600)
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, server.trackingCalls)
        assertEquals("2026-11-18T07:00:10Z", vm.state.value.tracking?.location?.capturedAt) // the older answer of 76 lost

        server.order = ApiResult.Success(order(OrderStatus.ARRIVED))
        vm.load()
        advanceUntilIdle()
        assertEquals(2, server.trackingCalls)
        assertNull(vm.state.value.tracking)
    }

    @Test
    fun `the end of the countdown reloads once per deadline`() = runTest(dispatcher) {
        val pending = order(OrderStatus.PENDING).let { o -> o.copy(assignments = o.assignments.map { it.copy(acceptanceDeadlineAt = "2026-11-18T03:10:00Z") }) }
        val server = FakeOrderRepository().apply { order = ApiResult.Success(pending); previews += ApiResult.Success(CancelPreview(0, emptyList(), "v1", "x")) }
        val vm = viewModel(server)
        vm.onDeadlinePassed()
        advanceUntilIdle()
        vm.onDeadlinePassed()
        advanceUntilIdle()
        assertEquals(2, server.orderCalls)
    }
}
