package vn.servio.customer.ui.support

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.customer.data.repository.FakeServerConfigRepository
import vn.servio.customer.data.repository.serverConfig

@OptIn(ExperimentalCoroutinesApi::class)
class SupportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val offline = ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")

    @Test
    fun `loads 107 when it opens`() = runTest(dispatcher) {
        val vm = SupportViewModel(FakeServerConfigRepository())
        assertEquals(UiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(UiState.Content(serverConfig()), vm.state.value)
    }

    @Test
    fun `a failure with no config in the session is the full-screen error, Thu lai asks again`() = runTest(dispatcher) {
        val config = FakeServerConfigRepository(offline)
        val vm = SupportViewModel(config)
        advanceUntilIdle()
        assertEquals(UiState.Error("Không có kết nối mạng"), vm.state.value)

        config.answer = ApiResult.Success(serverConfig())
        vm.load()
        advanceUntilIdle()
        assertEquals(2, config.calls)
        assertTrue(vm.state.value is UiState.Content)
    }

    @Test
    fun `the config of the session is shown at once`() = runTest(dispatcher) {
        val config = FakeServerConfigRepository().apply { latest = serverConfig(autoConfirmHours = 12) }
        val vm = SupportViewModel(config)
        assertEquals(UiState.Content(serverConfig(autoConfirmHours = 12)), vm.state.value)
        advanceUntilIdle()
        assertEquals(UiState.Content(serverConfig()), vm.state.value)
    }
}
