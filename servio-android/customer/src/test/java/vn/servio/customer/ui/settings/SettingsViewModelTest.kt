package vn.servio.customer.ui.settings

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Before
import org.junit.Test
import vn.servio.core.data.model.AuthResult
import vn.servio.core.data.model.LegalDocuments
import vn.servio.core.data.model.Me
import vn.servio.core.data.model.OtpRequestResult
import vn.servio.core.data.model.UpdateMeRequest
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.data.repository.LegalConsentRepository
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.repository.SessionCleanup
import vn.servio.customer.ui.settings.SettingsViewModel.Event
import vn.servio.customer.ui.settings.SettingsViewModel.LogoutState

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Records the order of the steps: "logout" (#6), "local" (session dropped on the device only), "cleanup"… */
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

        override fun clearLocalSession() {
            steps += "local"
        }
    }

    private inner class Cleanup : SessionCleanup {
        override fun signedOut() {
            steps += "cleanup"
        }
    }

    private inner class Legal(
        private val withdraw: ApiResult<Unit> = ApiResult.Success(Unit),
        private val delete: ApiResult<Unit> = ApiResult.Success(Unit),
    ) : LegalConsentRepository {
        override suspend fun legal() = ApiResult.Success(LegalDocuments("2026-11-15", "/chinh-sach-bao-mat", "/dieu-khoan-su-dung"))

        override suspend fun needsTermsConsent() = false

        override suspend fun grantTerms(policyVersion: String): ApiResult<Unit> = error("unused")

        override suspend fun withdrawTerms(): ApiResult<Unit> = withdraw.also { steps += "withdraw" }

        override suspend fun deleteAccount(): ApiResult<Unit> = delete.also { steps += "delete" }
    }

    private val blocked = ApiResult.Failure(
        AccountDeletion.BLOCKED,
        "Bạn còn yêu cầu đang mở. Hãy huỷ yêu cầu trước khi xoá tài khoản.",
    )

    @Test
    fun `logout calls 6, then closes the hubs and drops the account's data, then leaves`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = SettingsViewModel(Auth(gate), Cleanup(), Legal())
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

    @Test
    fun `withdrawing the consent signs out after the server recorded it`() = runTest(dispatcher) {
        val vm = SettingsViewModel(Auth(), Cleanup(), Legal())
        vm.withdrawConsent()
        advanceUntilIdle()
        assertEquals(listOf("withdraw", "logout", "cleanup"), steps)
        assertEquals(LogoutState.DONE, vm.logoutState.value)
    }

    @Test
    fun `a failed withdrawal keeps the user signed in`() = runTest(dispatcher) {
        val vm = SettingsViewModel(Auth(), Cleanup(), Legal(withdraw = ApiResult.Failure("NETWORK_ERROR", "Mất kết nối")))
        vm.withdrawConsent()
        advanceUntilIdle()
        assertEquals(listOf("withdraw"), steps)
        assertEquals(LogoutState.IDLE, vm.logoutState.value)
        assertEquals(Event.Message("Mất kết nối"), vm.events.first())
    }

    @Test
    fun `a deleted account drops the session on the device only, without 6`() = runTest(dispatcher) {
        val vm = SettingsViewModel(Auth(), Cleanup(), Legal())
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(listOf("delete", "local", "cleanup"), steps)
        assertEquals(LogoutState.DONE, vm.logoutState.value)
    }

    @Test
    fun `a blocked deletion shows the server's reason and stays signed in`() = runTest(dispatcher) {
        val vm = SettingsViewModel(Auth(), Cleanup(), Legal(delete = blocked))
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(listOf("delete"), steps)
        assertEquals(LogoutState.IDLE, vm.logoutState.value)
        assertEquals(Event.DeletionBlocked(blocked.message), vm.events.first())
    }

    @Test
    fun `the answer of DELETE users me maps to deleted, blocked or failed`() {
        assertEquals(AccountDeletion.Deleted, AccountDeletion.of(ApiResult.Success(Unit)))
        assertEquals(AccountDeletion.Blocked(blocked.message), AccountDeletion.of(blocked))
        assertEquals(
            AccountDeletion.Failed("Không có kết nối mạng"),
            AccountDeletion.of(ApiResult.Failure("NETWORK_ERROR", "Không có kết nối mạng")),
        )
        // Any other error code (e.g. the session expired meanwhile) is not shown as a reason to finish something.
        assertEquals(AccountDeletion.Failed("Phiên đăng nhập đã hết hạn"), AccountDeletion.of(ApiResult.Failure("UNAUTHENTICATED", "Phiên đăng nhập đã hết hạn")))
    }
}
