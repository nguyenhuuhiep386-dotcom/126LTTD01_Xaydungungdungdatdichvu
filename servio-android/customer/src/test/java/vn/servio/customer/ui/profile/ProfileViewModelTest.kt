package vn.servio.customer.ui.profile

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
import org.junit.Before
import org.junit.Test
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.customer.data.model.ProfileSummary
import vn.servio.customer.data.model.Wallet
import vn.servio.customer.data.repository.ProfileRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Server : ProfileRepository {
        val answers = ArrayDeque<ApiResult<ProfileSummary>>()
        var calls = 0

        override suspend fun getProfileSummary(): ApiResult<ProfileSummary> {
            calls++
            return answers.removeFirst()
        }
    }

    private fun summary(debt: Long) = ProfileSummary("Lê Văn Test", "+84911022155", null, Wallet(debtBalance = debt), 2, 3)

    @Test
    fun `each resume reloads and a failed reload keeps what is shown`() = runTest(dispatcher) {
        val server = Server().apply {
            answers += ApiResult.Success(summary(20_000))
            answers += ApiResult.Success(summary(0))
            answers += ApiResult.Failure("NETWORK_ERROR", "Mất kết nối")
        }
        val vm = ProfileViewModel(server)
        assertEquals(UiState.Loading, vm.state.value)

        vm.load()
        advanceUntilIdle()
        assertEquals(UiState.Content(summary(20_000)), vm.state.value)

        vm.load() // back from CS-30 after the admin confirmed
        assertEquals(UiState.Content(summary(20_000)), vm.state.value) // no loading screen over the content
        advanceUntilIdle()
        assertEquals(UiState.Content(summary(0)), vm.state.value)

        vm.load()
        advanceUntilIdle()
        assertEquals(UiState.Content(summary(0)), vm.state.value)
        assertFalse(vm.refreshing.value)
        assertEquals(3, server.calls)
    }

    @Test
    fun `the first load failing shows the error`() = runTest(dispatcher) {
        val server = Server().apply { answers += ApiResult.Failure("NETWORK_ERROR", "Mất kết nối") }
        val vm = ProfileViewModel(server)
        vm.load()
        advanceUntilIdle()
        assertEquals(UiState.Error("Mất kết nối"), vm.state.value)
    }
}
