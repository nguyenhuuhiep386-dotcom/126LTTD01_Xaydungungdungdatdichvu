package vn.servio.customer.ui.tracking

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
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
import vn.servio.customer.ui.tracking.OrderTrackingViewModel.Event

@OptIn(ExperimentalCoroutinesApi::class)
class OrderTrackingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class Partners : PartnerRepository {
        override suspend fun getPublicProfile(partnerId: String): ApiResult<PartnerPublic> =
            ApiResult.Success(PartnerPublic(id = partnerId, fullName = "Thợ Một", averageRating = 4.5, totalReviews = 2))

        override suspend fun getReviews(partnerId: String, page: Int, pageSize: Int): ApiResult<PagedResult<PartnerReview>> = error("unused")
    }

    private fun order(status: OrderStatus) = OrderDetail(
        id = "o1",
        code = "OD2611180010",
        status = status,
        assignments = listOf(OrderAssignment("a1", status, isPrimary = true, partner = OrderParty("p1", "Thợ Một"))),
    )

    private fun fix(assignment: String, at: String) = Tracking(
        "o1", assignment, assignmentStatus = OrderStatus.ON_THE_WAY,
        location = TrackingLocation(10.85, 106.77, 10, at), distanceKm = 2.5, etaMinutes = 6,
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(server: FakeOrderRepository, updates: FakeOrderUpdates, events: MutableList<Event>) =
        OrderTrackingViewModel(SavedStateHandle(mapOf("orderId" to "o1", "assignmentId" to "a1")), server, Partners(), updates).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            advanceUntilIdle()
        }

    @Test
    fun `opening on the way loads 63, 27 and 76`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order(OrderStatus.ON_THE_WAY))
            tracking = ApiResult.Success(fix("a1", "2026-11-18T07:00:00Z"))
        }
        val events = mutableListOf<Event>()
        val state = viewModel(server, FakeOrderUpdates(), events).state.value
        assertEquals(2, state.reviewCount)
        assertEquals(6, state.tracking?.etaMinutes)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `opened when no longer on the way it goes back at once`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.ARRIVED)) }
        val events = mutableListOf<Event>()
        viewModel(server, FakeOrderUpdates(), events)
        assertEquals(listOf<Event>(Event.Leave), events)
        assertEquals(0, server.trackingCalls)
    }

    @Test
    fun `a status change that ends on the way goes back once`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.ON_THE_WAY)) }
        val updates = FakeOrderUpdates()
        val events = mutableListOf<Event>()
        viewModel(server, updates, events)
        assertTrue(events.isEmpty())

        server.order = ApiResult.Success(order(OrderStatus.ARRIVED))
        updates.events.emit("O1")
        advanceTimeBy(600)
        runCurrent()
        updates.events.emit(null)
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.Leave), events)
    }

    @Test
    fun `only fixes of this assignment move the marker`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.ON_THE_WAY)) }
        val updates = FakeOrderUpdates()
        val vm = viewModel(server, updates, mutableListOf())
        updates.locationEvents.emit(fix("a2", "2026-11-18T07:00:20Z"))
        runCurrent()
        assertNull(vm.state.value.tracking)
        updates.locationEvents.emit(fix("A1", "2026-11-18T07:00:20Z"))
        runCurrent()
        assertEquals("2026-11-18T07:00:20Z", vm.state.value.tracking?.location?.capturedAt)
    }

    @Test
    fun `a disputed order is not tracked`() {
        val disputed = order(OrderStatus.ON_THE_WAY).copy(status = OrderStatus.DISPUTED)
        assertFalse(OrderTrackingViewModel.isOnTheWay(disputed, "a1"))
        assertTrue(OrderTrackingViewModel.isOnTheWay(order(OrderStatus.ON_THE_WAY), "A1"))
    }
}
