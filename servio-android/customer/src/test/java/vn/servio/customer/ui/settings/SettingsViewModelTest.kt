package vn.servio.customer.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import vn.servio.core.data.model.AuthResult
import vn.servio.core.data.model.Me
import vn.servio.core.data.model.OtpRequestResult
import vn.servio.core.data.model.UpdateMeRequest
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.repository.SessionCleanup
import vn.servio.customer.ui.settings.SettingsViewModel.LogoutState

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Records the order of the steps: "logout" (#6, which clears the tokens) then "cleanup". */
    private val steps = mutableListOf<String>()

    private inner class Auth(private val gate: CompletableDeferred<Unit>? = null) : AuthRepository {
        override val hasSession: Boolean = true

        override suspend fun requestOtp(phoneNumber: String): ApiResult<OtpRequestResult> = error("unused")

        override suspend fun verifyOtp(otpId: String, code: String): ApiResult<AuthResult> = error("unused")

        override suspend fun getMe(): ApiResult<Me> = error("unused")

        override suspend fun updateMe(request: UpdateMeRequest): ApiResult<Me> = error("unused")

        override suspend fun logout() {
            gate?.await()
            steps += "logout"
        }
    }

    private inner class Cleanup : SessionCleanup {
        override fun signedOut() {
            steps += "cleanup"
        }
    }

    @Test
    fun `logout calls 6, then closes the hubs and drops the account's data, then leaves`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = SettingsViewModel(Auth(gate), Cleanup())
        vm.logout()
        advanceUntilIdle()
        assertEquals(LogoutState.IN_PROGRESS, vm.logoutState.value)
        assertEquals(emptyList<String>(), steps) // nothing is dropped before #6 answered

        vm.logout() // a second tap while it runs does nothing
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("logout", "cleanup"), steps)
        assertEquals(LogoutState.DONE, vm.logoutState.value)
    }
}
