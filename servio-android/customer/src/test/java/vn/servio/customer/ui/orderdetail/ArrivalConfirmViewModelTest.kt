package vn.servio.customer.ui.orderdetail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.data.model.ArrivalConfirmationStatus
import vn.servio.customer.data.repository.ArrivalRequests
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.data.repository.FakeOrderUpdates
import vn.servio.customer.ui.orderdetail.ArrivalConfirmViewModel.Event
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalConfirmViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var now = Instant.parse("2026-11-18T07:00:00Z")
    private val request = ArrivalConfirmation("o1", "a1", "r1", ArrivalConfirmationStatus.PENDING, "2026-11-18T07:05:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(server: FakeOrderRepository, updates: FakeOrderUpdates, events: MutableList<Event>) =
        ArrivalConfirmViewModel(
            SavedStateHandle(mapOf("orderId" to "o1", "assignmentId" to "a1", "requestId" to "r1", "expiresAt" to request.expiresAt)),
            server,
            updates,
        ).also { vm ->
            vm.now = { now }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        }

    private fun updates() = FakeOrderUpdates(ArrivalRequests { now }).apply { arrivals.received(request) }

    @Test
    fun `counts down to expiresAt`() = runTest(dispatcher) {
        val vm = viewModel(FakeOrderRepository(), updates(), mutableListOf())
        assertEquals(300L, vm.secondsLeft())
        now = now.plusSeconds(48)
        assertEquals(252L, vm.secondsLeft())
    }

    @Test
    fun `both buttons are off while 179 runs, a double tap sends once`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { responds += ApiResult.Success(request.copy(status = ArrivalConfirmationStatus.CONFIRMED)) }
        val updates = updates()
        val events = mutableListOf<Event>()
        val vm = viewModel(server, updates, events)
        assertTrue(vm.state.value.buttonsEnabled)
        vm.answer(confirmed = true)
        assertFalse(vm.state.value.buttonsEnabled)
        vm.answer(confirmed = false)
        advanceUntilIdle()
        assertEquals(listOf("r1" to true), server.respondCalls)
        assertEquals(listOf<Event>(Event.Confirmed), events)
        assertNull(updates.pendingArrival("o1"))
    }

    @Test
    fun `not seen yet is sent as confirmed false`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { responds += ApiResult.Success(request.copy(status = ArrivalConfirmationStatus.DENIED)) }
        val updates = updates()
        val events = mutableListOf<Event>()
        viewModel(server, updates, events).answer(confirmed = false)
        advanceUntilIdle()
        assertEquals(listOf("r1" to false), server.respondCalls)
        assertEquals(listOf<Event>(Event.Denied), events)
        assertEquals(listOf("r1"), updates.answered)
    }

    @Test
    fun `409 closes as conflict and drops the request`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { responds += ApiResult.Failure("CONFLICT", "Yêu cầu xác nhận đã hết hạn") }
        val updates = updates()
        val events = mutableListOf<Event>()
        viewModel(server, updates, events).answer(confirmed = true)
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.Conflict), events)
        assertNull(updates.pendingArrival("o1"))
    }

    @Test
    fun `another error keeps the dialog with the message and the buttons on`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { responds += ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng") }
        val updates = updates()
        val events = mutableListOf<Event>()
        val vm = viewModel(server, updates, events)
        vm.answer(confirmed = true)
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertEquals("Không có kết nối mạng", vm.state.value.error)
        assertTrue(vm.state.value.buttonsEnabled)
        assertEquals("r1", updates.pendingArrival("o1")?.requestId)
    }

    @Test
    fun `past expiresAt nothing is sent and the dialog closes as expired`() = runTest(dispatcher) {
        val server = FakeOrderRepository()
        val events = mutableListOf<Event>()
        val vm = viewModel(server, updates(), events)
        now = Instant.parse("2026-11-18T07:05:00Z")
        vm.answer(confirmed = true)
        runCurrent()
        vm.expired()
        advanceUntilIdle()
        assertTrue(server.respondCalls.isEmpty())
        assertEquals(listOf<Event>(Event.Expired), events)
    }
}
