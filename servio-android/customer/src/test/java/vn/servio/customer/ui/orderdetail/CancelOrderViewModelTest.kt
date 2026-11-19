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
import vn.servio.customer.data.model.AssignmentFee
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.CancelResult
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.ui.orderdetail.CancelOrderViewModel.Event
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CancelOrderViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-18T03:00:00Z")
    private val reason = "Tôi muốn đổi lịch"

    private fun preview(fee: Long, status: OrderStatus, version: String = "v1", expires: String = "2026-11-18T03:01:00Z") =
        CancelPreview(fee, listOf(AssignmentFee("a1", status, fee)), version, expires)

    private val free = preview(0, OrderStatus.ACCEPTED)
    private val onTheWay = preview(20_000, OrderStatus.ON_THE_WAY)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(server: FakeOrderRepository, events: MutableList<Event>, handle: SavedStateHandle = SavedStateHandle(mapOf("orderId" to "o1"))) =
        CancelOrderViewModel(handle, server).also { vm ->
            vm.now = { now }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            advanceUntilIdle()
        }

    // region Dialog content

    @Test
    fun `free cancel has no fee box, no debt note and a plain label`() {
        val ui = CancelDialogUi.of(free, 100_000, reasonChosen = true, sending = false, loading = false)
        assertFalse(ui.showFee)
        assertNull(ui.feeReason)
        assertNull(ui.debtLimit)
        assertFalse(ui.confirmWithFee)
        assertTrue(ui.confirmEnabled)
    }

    @Test
    fun `paid cancel names why, the amount of 151 and the limit of 87`() {
        val ui = CancelDialogUi.of(onTheWay, 100_000, reasonChosen = true, sending = false, loading = false)
        assertTrue(ui.showFee)
        assertEquals(20_000L, ui.fee)
        assertEquals(CancelDialogUi.FeeReason.ON_THE_WAY, ui.feeReason)
        assertEquals(100_000L, ui.debtLimit)
        assertTrue(ui.confirmWithFee)

        val arrived = CancelDialogUi.of(preview(30_000, OrderStatus.ARRIVED), null, reasonChosen = true, sending = false, loading = false)
        assertEquals(CancelDialogUi.FeeReason.ARRIVED, arrived.feeReason)
        assertNull(arrived.debtLimit)
    }

    @Test
    fun `confirm needs a loaded preview and a reason, and not while sending`() {
        assertFalse(CancelDialogUi.of(free, null, reasonChosen = false, sending = false, loading = false).confirmEnabled)
        assertFalse(CancelDialogUi.of(free, null, reasonChosen = true, sending = true, loading = false).confirmEnabled)
        assertFalse(CancelDialogUi.of(null, null, reasonChosen = true, sending = false, loading = true).confirmEnabled)
        assertFalse(CancelDialogUi.of(free, null, reasonChosen = true, sending = false, loading = true).confirmEnabled)
    }

    // endregion

    @Test
    fun `opening asks 151 and 87, the reason survives a new view model`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { previews += ApiResult.Success(onTheWay) }
        val handle = SavedStateHandle(mapOf("orderId" to "o1"))
        val vm = viewModel(server, mutableListOf(), handle)
        assertEquals(onTheWay, vm.state.value.preview)
        assertEquals(100_000L, vm.state.value.debtLimit)
        assertNull(vm.state.value.reasonIndex)

        vm.selectReason(1)
        val again = viewModel(server, mutableListOf(), handle)
        assertEquals(1, again.state.value.reasonIndex)
        assertEquals(2, server.previewCalls)
    }

    @Test
    fun `sends the reason as written with the rowVersion of the preview`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            previews += ApiResult.Success(onTheWay)
            cancels += ApiResult.Success(CancelResult("o1", OrderStatus.CANCELLED, 20_000))
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        vm.selectReason(1)
        vm.confirm(reason)
        advanceUntilIdle()
        assertEquals(listOf(Triple("o1", reason, "v1")), server.cancelCalls)
        assertEquals(listOf<Event>(Event.Cancelled(20_000)), events)
    }

    @Test
    fun `nothing is sent without a reason`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { previews += ApiResult.Success(free) }
        val vm = viewModel(server, mutableListOf())
        vm.confirm(reason)
        advanceUntilIdle()
        assertTrue(server.cancelCalls.isEmpty())
    }

    @Test
    fun `an expired preview with a new fee is shown and waits for another tap`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            previews += ApiResult.Success(preview(0, OrderStatus.ACCEPTED, expires = "2026-11-18T02:59:00Z"))
            previews += ApiResult.Success(preview(20_000, OrderStatus.ON_THE_WAY, version = "v2"))
            cancels += ApiResult.Success(CancelResult("o1", OrderStatus.CANCELLED, 20_000))
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        vm.selectReason(0)
        vm.confirm(reason)
        advanceUntilIdle()
        assertTrue(server.cancelCalls.isEmpty())
        assertEquals(20_000L, vm.state.value.preview?.fee)
        assertTrue(vm.state.value.feeChanged)
        assertFalse(vm.state.value.sending)

        vm.confirm(reason)
        advanceUntilIdle()
        assertEquals(listOf(Triple("o1", reason, "v2")), server.cancelCalls)
        assertEquals(listOf<Event>(Event.Cancelled(20_000)), events)
    }

    @Test
    fun `an expired preview with the same fee is sent with the new rowVersion`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            previews += ApiResult.Success(preview(0, OrderStatus.ACCEPTED, expires = "2026-11-18T02:59:00Z"))
            previews += ApiResult.Success(preview(0, OrderStatus.ACCEPTED, version = "v2"))
            cancels += ApiResult.Success(CancelResult("o1", OrderStatus.CANCELLED, 0))
        }
        val vm = viewModel(server, mutableListOf())
        vm.selectReason(0)
        vm.confirm(reason)
        advanceUntilIdle()
        assertEquals(listOf(Triple("o1", reason, "v2")), server.cancelCalls)
    }

    @Test
    fun `409 asks 151 again, a new fee waits, the same fee is retried once`() = runTest(dispatcher) {
        val conflict = ApiResult.Failure(CancelOrderViewModel.RESOURCE_VERSION_CONFLICT, "Đơn vừa thay đổi")
        val server = FakeOrderRepository().apply {
            previews += ApiResult.Success(free)
            previews += ApiResult.Success(preview(20_000, OrderStatus.ON_THE_WAY, version = "v2"))
            cancels += conflict
        }
        val vm = viewModel(server, mutableListOf())
        vm.selectReason(0)
        vm.confirm(reason)
        advanceUntilIdle()
        assertEquals(1, server.cancelCalls.size)
        assertEquals(20_000L, vm.state.value.preview?.fee)
        assertTrue(vm.state.value.feeChanged)

        val sameFee = FakeOrderRepository().apply {
            previews += ApiResult.Success(free)
            previews += ApiResult.Success(preview(0, OrderStatus.ACCEPTED, version = "v2"))
            cancels += conflict
            cancels += ApiResult.Success(CancelResult("o1", OrderStatus.CANCELLED, 0))
        }
        val events = mutableListOf<Event>()
        val retried = viewModel(sameFee, events)
        retried.selectReason(0)
        retried.confirm(reason)
        advanceUntilIdle()
        assertEquals(listOf("v1", "v2"), sameFee.cancelCalls.map { it.third })
        assertEquals(listOf<Event>(Event.Cancelled(0)), events)
    }

    @Test
    fun `422 closes the dialog as not cancellable`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            previews += ApiResult.Success(onTheWay)
            cancels += ApiResult.Failure(CancelOrderViewModel.ORDER_NOT_CANCELLABLE, "Không thể huỷ")
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        vm.selectReason(2)
        vm.confirm(reason)
        advanceUntilIdle()
        assertEquals(listOf<Event>(Event.NotCancellable), events)
    }

    @Test
    fun `other errors keep the dialog with the message`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            previews += ApiResult.Success(onTheWay)
            cancels += ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        vm.selectReason(2)
        vm.confirm(reason)
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertEquals("Không có kết nối mạng", vm.state.value.sendError)
        assertFalse(vm.state.value.sending)
        assertTrue(vm.state.value.ui.confirmEnabled)
    }

    @Test
    fun `a failed 151 shows the error, the debt limit failing is not fatal`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            previews += ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
            debtLimit = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")
        }
        val vm = viewModel(server, mutableListOf())
        assertEquals("Không có kết nối mạng", vm.state.value.loadError)

        server.previews.clear()
        server.previews += ApiResult.Success(onTheWay)
        vm.load()
        advanceUntilIdle()
        assertEquals(onTheWay, vm.state.value.preview)
        assertNull(vm.state.value.debtLimit)
    }
}
