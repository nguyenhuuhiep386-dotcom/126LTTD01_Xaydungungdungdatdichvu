package vn.servio.customer.ui.order

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import vn.servio.customer.data.model.OrderListFilter
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.data.repository.FakeOrderUpdates

@OptIn(ExperimentalCoroutinesApi::class)
class MyOrdersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun page(total: Int, vararg ids: String) =
        ApiResult.Success(PagedResult(ids.map { OrderSummary(id = it, code = it) }, total, 1, 20, 1))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the active count is unknown until 62 answers, then the server's totalCount`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { lists[OrderListFilter.ACTIVE] = page(3, "a", "b", "c") }
        val vm = MyOrdersViewModel(server, FakeOrderUpdates())
        vm.refresh()
        assertNull(vm.state.value.tabs.getValue(OrderListFilter.ACTIVE).totalCount)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.tabs.getValue(OrderListFilter.ACTIVE).totalCount)
        assertEquals(listOf(OrderListFilter.ACTIVE to 1), server.listCalls)
    }

    @Test
    fun `a failed first load is an error, not a zero`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { lists[OrderListFilter.ACTIVE] = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng") }
        val vm = MyOrdersViewModel(server, FakeOrderUpdates())
        vm.refresh()
        advanceUntilIdle()
        val active = vm.state.value.tabs.getValue(OrderListFilter.ACTIVE)
        assertEquals("Không có kết nối mạng", active.error)
        assertNull(active.totalCount)
    }

    @Test
    fun `the selected tab is kept and refreshed with the active count`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply {
            lists[OrderListFilter.ACTIVE] = page(1, "a")
            lists[OrderListFilter.CANCELLED] = page(2, "x", "y")
        }
        val vm = MyOrdersViewModel(server, FakeOrderUpdates())
        vm.refresh()
        advanceUntilIdle()
        vm.selectTab(OrderListFilter.CANCELLED)
        advanceUntilIdle()
        assertEquals(listOf("x", "y"), vm.state.value.current.items.map { it.id })

        server.listCalls.clear()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(OrderListFilter.CANCELLED, vm.state.value.selected)
        assertEquals(setOf(OrderListFilter.ACTIVE to 1, OrderListFilter.CANCELLED to 1), server.listCalls.toSet())
        assertFalse(vm.state.value.tabs.getValue(OrderListFilter.COMPLETED).fetched)
    }

    @Test
    fun `order changes reload the open tab and the count once, without a spinner`() = runTest(dispatcher) {
        val server = FakeOrderRepository().apply { lists[OrderListFilter.ACTIVE] = page(1, "a") }
        val updates = FakeOrderUpdates()
        val vm = MyOrdersViewModel(server, updates)
        vm.refresh()
        advanceUntilIdle()
        server.listCalls.clear()

        server.lists[OrderListFilter.ACTIVE] = page(2, "b", "a")
        updates.events.emit("a")
        updates.events.emit("b")
        updates.events.emit(null)
        runCurrent()
        assertFalse(vm.state.value.current.loading)
        advanceTimeBy(600)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(OrderListFilter.ACTIVE to 1), server.listCalls)
        assertEquals(2, vm.state.value.current.totalCount)
        assertTrue(vm.state.value.current.items.map { it.id } == listOf("b", "a"))
    }
}
