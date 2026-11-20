package vn.servio.customer.ui.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.data.model.UnreadCount
import vn.servio.customer.data.repository.NotificationRepository

private class FakeNotificationRepository : NotificationRepository {
    val pages = mutableMapOf<Int, ApiResult<PagedResult<AppNotification>>>()
    var unread: ApiResult<UnreadCount> = ApiResult.Success(UnreadCount(0))
    var readAllAnswer: ApiResult<Unit> = ApiResult.Success(Unit)
    val readIds = mutableListOf<String>()
    var readAllCalls = 0

    override suspend fun list(page: Int) = pages[page] ?: ApiResult.Success(PagedResult(emptyList(), 0, page, 20, 0))
    override suspend fun read(id: String): ApiResult<Unit> = ApiResult.Success(Unit).also { readIds += id }
    override suspend fun readAll(): ApiResult<Unit> = readAllAnswer.also { readAllCalls++ }
    override suspend fun unreadCount() = unread
}

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun n(id: String, isRead: Boolean = false) = AppNotification(id = id, isRead = isRead, createdAt = "2026-11-20T10:00:00+07:00")

    private fun page(page: Int, total: Int, vararg items: AppNotification) =
        ApiResult.Success(PagedResult(items.toList(), total, page, 20, (total + 19) / 20))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening marks nothing read, the next page drops rows already shown`() = runTest(dispatcher) {
        val repo = FakeNotificationRepository().apply {
            pages[1] = page(1, 3, n("a"), n("b"))
            pages[2] = page(2, 3, n("b"), n("c"))
            unread = ApiResult.Success(UnreadCount(3))
        }
        val vm = NotificationsViewModel(repo)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.id })
        assertTrue(repo.readIds.isEmpty())
        assertEquals(0, repo.readAllCalls)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `a tap reads the row at once and lowers the count`() = runTest(dispatcher) {
        val repo = FakeNotificationRepository().apply {
            pages[1] = page(1, 2, n("a"), n("b", isRead = true))
            unread = ApiResult.Success(UnreadCount(1))
        }
        val vm = NotificationsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.canReadAll)

        vm.markRead(n("a"))
        assertTrue(vm.state.value.items.first { it.id == "a" }.isRead)
        assertEquals(0, vm.state.value.unread)
        assertFalse(vm.state.value.canReadAll)
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.readIds)

        vm.markRead(n("b", isRead = true))
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.readIds)
    }

    @Test
    fun `read all failing keeps the rows unread and says so`() = runTest(dispatcher) {
        val repo = FakeNotificationRepository().apply {
            pages[1] = page(1, 1, n("a"))
            unread = ApiResult.Success(UnreadCount(5))
            readAllAnswer = ApiResult.Failure(code = "NETWORK", message = "Mất kết nối")
        }
        val vm = NotificationsViewModel(repo)
        advanceUntilIdle()

        vm.readAll()
        advanceUntilIdle()
        assertFalse(vm.state.value.items.single().isRead)
        assertEquals(5, vm.state.value.unread)
        assertEquals(NotificationsViewModel.Event.ReadAllFailed, vm.events.first())

        repo.readAllAnswer = ApiResult.Success(Unit)
        vm.readAll()
        advanceUntilIdle()
        assertTrue(vm.state.value.items.single().isRead)
        assertEquals(0, vm.state.value.unread)
        assertFalse(vm.state.value.canReadAll)
    }

    @Test
    fun `first load failing shows the error, retry loads`() = runTest(dispatcher) {
        val repo = FakeNotificationRepository().apply { pages[1] = ApiResult.Failure(code = "NETWORK", message = "x") }
        val vm = NotificationsViewModel(repo)
        advanceUntilIdle()
        assertEquals("x", vm.state.value.error)

        repo.pages[1] = page(1, 0)
        vm.load()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
    }
}
