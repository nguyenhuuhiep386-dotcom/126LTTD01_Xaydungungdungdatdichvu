package vn.servio.customer.ui.completion

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
import vn.servio.customer.R
import vn.servio.customer.data.model.ConfirmCompletionResult
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.TimelineEntry
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.data.repository.FakeOrderUpdates
import vn.servio.customer.ui.completion.ConfirmCompletionViewModel.Event

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmCompletionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun order(status: OrderStatus = OrderStatus.COMPLETED_BY_PARTNER, rowVersion: String = "v1") = OrderDetail(
        id = "o1",
        code = "OD2611180010",
        status = status,
        rowVersion = rowVersion,
        assignments = listOf(OrderAssignment("a1", status, isPrimary = true)),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(server: FakeOrderRepository, events: MutableList<Event>, updates: FakeOrderUpdates = FakeOrderUpdates()) =
        ConfirmCompletionViewModel(SavedStateHandle(mapOf("orderId" to "o1")), server, updates).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            vm.load()
            advanceUntilIdle()
        }

    @Test
    fun `63 and 64 load, the note comes from 64 and a failed 64 only hides it`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order())
            timeline = ApiResult.Success(listOf(TimelineEntry(OrderStatus.COMPLETED_BY_PARTNER, note = "Đã xong")))
        }
        val vm = viewModel(server, mutableListOf())
        assertEquals("Đã xong", vm.state.value.note)

        val failing = FakeOrderRepository().apply {
            order = ApiResult.Success(order())
            timeline = ApiResult.Failure("NETWORK_ERROR", "x")
        }
        val events = mutableListOf<Event>()
        val other = viewModel(failing, events)
        assertNull(other.state.value.note)
        assertEquals(OrderStatus.COMPLETED_BY_PARTNER, other.state.value.detail?.status)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `opened on an order that no longer waits it leaves`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order(OrderStatus.COMPLETED)) }
        val events = mutableListOf<Event>()
        viewModel(server, events)
        assertEquals(listOf<Event>(Event.NotWaiting), events)
    }

    @Test
    fun `confirming sends the rowVersion once and opens the review of the primary part`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order(rowVersion = "rv7"))
            confirms += ApiResult.Success(ConfirmCompletionResult("o1", OrderStatus.COMPLETED, false, 320_000, 320_000))
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        vm.confirm()
        assertTrue(vm.state.value.sending)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("rv7"), server.confirmCalls)
        assertEquals(listOf<Event>(Event.Confirmed("a1")), events)
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `the auto confirmation after our own 71 does not say not waiting`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order())
            confirms += ApiResult.Success(ConfirmCompletionResult("o1", OrderStatus.COMPLETED))
        }
        val updates = FakeOrderUpdates()
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events, updates)
        vm.confirm()
        advanceUntilIdle()
        server.order = ApiResult.Success(order(OrderStatus.COMPLETED))
        updates.events.emit("o1")
        advanceTimeBy(600)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.Confirmed("a1")), events)
    }

    @Test
    fun `a status change to completed while waiting leaves once`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { order = ApiResult.Success(order()) }
        val updates = FakeOrderUpdates()
        val events = mutableListOf<Event>()
        viewModel(server, events, updates)
        server.order = ApiResult.Success(order(OrderStatus.COMPLETED))
        updates.events.emit("O1")
        advanceTimeBy(600)
        runCurrent()
        updates.events.emit(null)
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.NotWaiting), events)
    }

    @Test
    fun `409 version conflict reloads 63 and asks to check again`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            order = ApiResult.Success(order(rowVersion = "v1"))
            confirms += ApiResult.Failure("RESOURCE_VERSION_CONFLICT", "Đơn đã thay đổi")
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        server.order = ApiResult.Success(order(rowVersion = "v2"))
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.Message(res = R.string.confirm_changed)), events)
        assertEquals("v2", vm.state.value.detail?.rowVersion)
        assertEquals(2, server.orderCalls)
    }

    @Test
    fun `409 invalid transition leaves, disputed and other errors stay with a message`() = runTest(dispatcher) {
        listOf(
            "INVALID_STATUS_TRANSITION" to Event.NotWaiting,
            "ORDER_DISPUTED" to Event.Message(res = R.string.confirm_disputed),
            "NETWORK_ERROR" to Event.Message(text = "Không có kết nối mạng"),
        ).forEach { (code, expected) ->
            val server = FakeOrderRepository().apply {
                order = ApiResult.Success(order())
                confirms += ApiResult.Failure(code, "Không có kết nối mạng")
            }
            val events = mutableListOf<Event>()
            val vm = viewModel(server, events)
            vm.confirm()
            advanceUntilIdle()
            assertEquals(listOf(expected), events)
            assertFalse(vm.state.value.sending)
        }
    }
}
