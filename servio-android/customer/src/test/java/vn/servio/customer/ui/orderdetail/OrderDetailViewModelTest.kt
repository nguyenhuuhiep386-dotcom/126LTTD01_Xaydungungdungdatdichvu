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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.ExtraChargeStatus
import vn.servio.customer.ui.orderdetail.OrderDetailViewModel.Event
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderParty
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.model.PartnerReview
import vn.servio.customer.data.model.PendingReview
import vn.servio.customer.data.model.Tracking
import vn.servio.customer.data.model.TrackingLocation
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.data.repository.FakeOrderUpdates
import vn.servio.customer.data.repository.FakeReviewRepository
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

    private fun TestScope.viewModel(server: FakeOrderRepository, partners: Partners = Partners(), updates: FakeOrderUpdates = FakeOrderUpdates(), reviews: FakeReviewRepository = FakeReviewRepository()) =
        OrderDetailViewModel(SavedStateHandle(mapOf("orderId" to orderId)), server, partners, updates, reviews).also {
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

    private val charge = ExtraCharge(id = "ec1", assignmentId = "a1", description = "Thay tụ", amount = 120_000, status = ExtraChargeStatus.PENDING, createdAt = "2026-11-18T08:00:00Z")

    private fun withCharge(vararg charges: ExtraCharge) = order(OrderStatus.IN_PROGRESS).copy(extraCharges = charges.toList())

    private fun TestScope.collect(vm: OrderDetailViewModel): MutableList<Event> =
        mutableListOf<Event>().also { events -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } } }

    @Test
    fun `approving marks the charge at once, says so and reloads for the server totals`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(withCharge(charge))
            chargeAnswers += ApiResult.Success(charge.copy(status = ExtraChargeStatus.APPROVED))
        }
        val vm = viewModel(server)
        val events = collect(vm)
        assertEquals("ec1", vm.state.value.chargeToOpen(includeClosed = false)?.id)

        server.order = ApiResult.Success(withCharge(charge.copy(status = ExtraChargeStatus.APPROVED)))
        vm.answerCharge("ec1", approved = true)
        assertEquals("ec1", vm.state.value.answeringCharge)
        vm.answerCharge("ec1", approved = false) // second tap while running: ignored
        advanceUntilIdle()
        assertEquals(listOf("ec1" to true), server.chargeCalls)
        assertEquals(listOf<Event>(Event.ChargeAnswered(approved = true, amount = 120_000)), events)
        assertNull(vm.state.value.answeringCharge)
        assertNull(vm.state.value.chargeToOpen(includeClosed = true))
        assertEquals(2, server.orderCalls)
    }

    @Test
    fun `a 409 or 404 closes the sheet with its message and reloads, the charge held until then`() = runTest(dispatcher) {
        listOf(
            "INVALID_STATUS_TRANSITION" to OrderDetailUi.ChargeFailure.HANDLED,
            "ORDER_DISPUTED" to OrderDetailUi.ChargeFailure.DISPUTED,
            "NOT_FOUND" to OrderDetailUi.ChargeFailure.NOT_FOUND,
        ).forEach { (code, failure) ->
            val server = FakeOrderRepository().apply {
                order = ApiResult.Success(withCharge(charge))
                chargeAnswers += ApiResult.Failure(code, "x")
            }
            val vm = viewModel(server)
            val events = collect(vm)
            val states = mutableListOf<OrderDetailViewModel.State>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { states += it } }
            vm.answerCharge("ec1", approved = false)
            advanceUntilIdle()
            assertEquals(listOf<Event>(Event.ChargeFailed(failure)), events)
            val held = states.single { it.heldCharge != null }
            assertEquals("ec1", held.heldCharge)
            assertNull(held.chargeToOpen(includeClosed = true)) // the sheet closes and does not reopen at once
            assertEquals(2, server.orderCalls)
            assertNull(vm.state.value.heldCharge) // the reload decides again
        }
    }

    @Test
    fun `another error keeps the sheet with the message and the buttons on`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(withCharge(charge))
            chargeAnswers += ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
        }
        val vm = viewModel(server)
        val events = collect(vm)
        vm.answerCharge("ec1", approved = true)
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertEquals("Không có kết nối mạng", vm.state.value.chargeError)
        assertNull(vm.state.value.answeringCharge)
        assertEquals("ec1", vm.state.value.chargeToOpen(includeClosed = false)?.id)
        assertEquals(1, server.orderCalls)
    }

    @Test
    fun `a closed charge is kept closed across a rotation and a reload`() = runTest(dispatcher) {
        val second = charge.copy(id = "ec2", createdAt = "2026-11-18T08:05:00Z")
        val server = FakeOrderRepository().apply { order = ApiResult.Success(withCharge(charge, second)) }
        val handle = SavedStateHandle(mapOf("orderId" to orderId))
        val vm = OrderDetailViewModel(handle, server, Partners(), FakeOrderUpdates(), FakeReviewRepository()).also { it.load(); advanceUntilIdle() }
        vm.chargeClosed("ec1")
        assertEquals("ec2", vm.state.value.chargeToOpen(includeClosed = false)?.id)
        assertEquals("ec1", vm.state.value.chargeToOpen(includeClosed = true)?.id)

        val restored = OrderDetailViewModel(handle, server, Partners(), FakeOrderUpdates(), FakeReviewRepository()).also { it.load(); advanceUntilIdle() }
        assertEquals(setOf("ec1"), restored.state.value.closedCharges)
        assertEquals("ec2", restored.state.value.chargeToOpen(includeClosed = false)?.id)
    }

    @Test
    fun `an extra charge request of this order reloads it`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(withCharge()) }
        val updates = FakeOrderUpdates()
        val vm = viewModel(server, updates = updates)
        assertNull(vm.state.value.chargeToOpen(includeClosed = false))
        server.order = ApiResult.Success(withCharge(charge))
        updates.events.emit(orderId) // HubOrderUpdates.changes() also carries ExtraChargeRequested
        advanceUntilIdle()
        assertEquals("ec1", vm.state.value.chargeToOpen(includeClosed = false)?.id)
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

    @Test
    fun `a completed order asks 100 and loses the review button once reviewed`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.COMPLETED)) }
        val reviews = FakeReviewRepository().apply {
            pending = ApiResult.Success(listOf(PendingReview(orderId, "A1", "OD2611180010", "Thợ Một", "x", "2026-11-23T08:00:00Z")))
        }
        val vm = viewModel(server, reviews = reviews)
        assertTrue(vm.state.value.reviewable)
        assertEquals(1, reviews.pendingCalls)

        reviews.pending = ApiResult.Success(emptyList())
        vm.onResumed()
        advanceUntilIdle()
        assertFalse(vm.state.value.reviewable)
        assertEquals(2, reviews.pendingCalls)
    }

    @Test
    fun `an order not completed never asks 100`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.IN_PROGRESS)) }
        val reviews = FakeReviewRepository()
        val vm = viewModel(server, reviews = reviews)
        vm.onResumed()
        advanceUntilIdle()
        assertFalse(vm.state.value.reviewable)
        assertEquals(0, reviews.pendingCalls)
    }
}
