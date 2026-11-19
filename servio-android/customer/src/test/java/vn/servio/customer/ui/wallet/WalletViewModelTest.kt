package vn.servio.customer.ui.wallet

import android.net.Uri
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
import vn.servio.core.data.model.AuthResult
import vn.servio.core.data.model.Me
import vn.servio.core.data.model.OtpRequestResult
import vn.servio.core.data.model.UpdateMeRequest
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.network.ApiResult
import vn.servio.customer.R
import vn.servio.customer.data.image.CaptureFiles
import vn.servio.customer.data.model.BankAccount
import vn.servio.customer.data.model.Debt
import vn.servio.customer.data.model.DebtPaymentBody
import vn.servio.customer.data.model.DebtPaymentResult
import vn.servio.customer.data.model.UploadedFile
import vn.servio.customer.data.model.Wallet
import vn.servio.customer.data.repository.FakeServerConfigRepository
import vn.servio.customer.data.repository.FileRepository
import vn.servio.customer.data.repository.WalletRepository
import vn.servio.customer.data.repository.serverConfig
import vn.servio.customer.ui.wallet.WalletForm.EvidenceStatus
import vn.servio.customer.ui.wallet.WalletViewModel.Event

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val fee = Debt("d1", "CANCELLATION_FEE", 20_000, "Phí huỷ đơn", "OD2611180012", null, "2026-11-17T22:49:58Z")

    private class Server : WalletRepository {
        val wallets = ArrayDeque<ApiResult<Wallet>>()
        val debts = ArrayDeque<ApiResult<List<Debt>>>()
        val declarations = ArrayDeque<ApiResult<DebtPaymentResult>>()
        val sent = mutableListOf<DebtPaymentBody>()
        var loads = 0

        override suspend fun getWallet(): ApiResult<Wallet> = if (wallets.size > 1) wallets.removeFirst() else wallets.first()

        override suspend fun getDebts(): ApiResult<List<Debt>> {
            loads++
            return if (debts.size > 1) debts.removeFirst() else debts.first()
        }

        override suspend fun declarePayment(body: DebtPaymentBody): ApiResult<DebtPaymentResult> {
            sent += body
            return declarations.removeFirst()
        }
    }

    private object NoFiles : FileRepository {
        override suspend fun uploadPostImage(uri: Uri): ApiResult<UploadedFile> = error("unused")

        override suspend fun uploadEvidence(uri: Uri): ApiResult<UploadedFile> = error("unused")
    }

    private object NoCamera : CaptureFiles {
        override fun create(): Uri = error("unused")

        override fun hasContent(uri: Uri): Boolean = error("unused")

        override fun delete(uri: Uri) = error("unused")
    }

    /** #9 for the transfer note: [me] is what it answers. */
    private class Account(var me: ApiResult<Me> = ApiResult.Success(Me("u1", "+84912345678", "Khách Một"))) : AuthRepository {
        override val hasSession: Boolean = true

        override suspend fun requestOtp(phoneNumber: String): ApiResult<OtpRequestResult> = error("unused")

        override suspend fun verifyOtp(otpId: String, code: String): ApiResult<AuthResult> = error("unused")

        override suspend fun getMe(): ApiResult<Me> = me

        override suspend fun updateMe(request: UpdateMeRequest): ApiResult<Me> = error("unused")

        override suspend fun logout() = error("unused")
    }

    private val bank = BankAccount("Vietcombank", "0123456789", "CONG TY SERVIO", "SERVIO {phone}")

    /** A receipt already uploaded (as after a rotation): no android.net.Uri is needed by the ViewModel for it. */
    private fun uploaded(reference: String = "FT2611190001") =
        SavedStateHandle(mapOf("reference" to reference, "evidenceUri" to "content://media/picker/1", "evidenceUrl" to "/api/v1/files/f1"))

    private fun TestScope.viewModel(
        server: Server,
        events: MutableList<Event>,
        handle: SavedStateHandle = uploaded(),
        config: FakeServerConfigRepository = FakeServerConfigRepository(),
        account: Account = Account(),
    ) =
        WalletViewModel(handle, server, NoFiles, NoCamera, config, account).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            advanceUntilIdle()
        }

    @Test
    fun `owes, waiting and clear`() = runTest(dispatcher) {
        val server = Server().apply {
            wallets += ApiResult.Success(Wallet(debtBalance = 20_000, maxDebt = 100_000))
            debts += ApiResult.Success(listOf(fee))
        }
        val vm = viewModel(server, mutableListOf(), SavedStateHandle())
        assertEquals(WalletForm.Status.OWES, vm.state.value.status)
        assertEquals(100_000L, vm.state.value.wallet?.maxDebt)
        assertFalse(vm.state.value.canSubmit) // no reference, no photo yet

        server.wallets.apply { clear(); add(ApiResult.Success(Wallet(debtBalance = 20_000, pendingPayment = 20_000, maxDebt = 100_000))) }
        server.debts.apply { clear(); add(ApiResult.Success(listOf(fee.copy(pendingTransactionCode = "TX2611190001")))) }
        vm.load()
        advanceUntilIdle()
        assertEquals(WalletForm.Status.WAITING, vm.state.value.status)
        assertTrue(vm.state.value.undeclared.isEmpty())

        server.wallets.apply { clear(); add(ApiResult.Success(Wallet(maxDebt = 100_000))) }
        server.debts.apply { clear(); add(ApiResult.Success(emptyList())) }
        vm.load()
        advanceUntilIdle()
        assertEquals(WalletForm.Status.CLEAR, vm.state.value.status)
    }

    @Test
    fun `a first load failing shows the error, a reload failing keeps the data`() = runTest(dispatcher) {
        val server = Server().apply {
            wallets += ApiResult.Failure("NETWORK_ERROR", "Mất kết nối")
            debts += ApiResult.Success(listOf(fee))
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events, SavedStateHandle())
        assertFalse(vm.state.value.loaded)
        assertEquals("Mất kết nối", vm.state.value.error)

        server.wallets.apply { clear(); add(ApiResult.Success(Wallet(debtBalance = 20_000, maxDebt = 100_000))) }
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)

        server.debts.apply { clear(); add(ApiResult.Failure("NETWORK_ERROR", "Mất kết nối")) }
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.error)
        assertEquals(Event.Message(text = "Mất kết nối"), events.last())
    }

    @Test
    fun `send declares every undeclared fee once, then reloads`() = runTest(dispatcher) {
        val declared = fee.copy(id = "d0", pendingTransactionCode = "TX2611180099")
        val server = Server().apply {
            wallets += ApiResult.Success(Wallet(debtBalance = 40_000, pendingPayment = 20_000, maxDebt = 100_000))
            debts += ApiResult.Success(listOf(declared, fee))
            declarations += ApiResult.Success(DebtPaymentResult("t1", "TX2611190001", 20_000, "PENDING", "FT2611190001"))
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events, uploaded(reference = "  FT2611190001 "))
        assertEquals(EvidenceStatus.UPLOADED, vm.state.value.evidence?.status)
        assertTrue(vm.state.value.canSubmit)

        vm.submit()
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf(DebtPaymentBody(listOf("d1"), "FT2611190001", "/api/v1/files/f1")), server.sent)
        assertEquals(Event.Sent(20_000), events.single())
        assertNull(vm.state.value.evidence)
        assertEquals("", vm.state.value.reference)
        assertEquals(2, server.loads)
    }

    @Test
    fun `NOTHING_TO_PAY says so and reloads`() = runTest(dispatcher) {
        val server = Server().apply {
            wallets += ApiResult.Success(Wallet(debtBalance = 20_000, maxDebt = 100_000))
            debts += ApiResult.Success(listOf(fee))
            declarations += ApiResult.Failure("NOTHING_TO_PAY", "Có khoản phí đã thanh toán hoặc đang chờ xác nhận, vui lòng tải lại")
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        vm.submit()
        advanceUntilIdle()
        assertEquals(Event.Message(res = R.string.wallet_nothing_to_pay), events.single())
        assertEquals(2, server.loads)
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `a 400 on the reference shows under the field until the text changes`() = runTest(dispatcher) {
        val server = Server().apply {
            wallets += ApiResult.Success(Wallet(debtBalance = 20_000, maxDebt = 100_000))
            debts += ApiResult.Success(listOf(fee))
            declarations += ApiResult.Failure("VALIDATION_ERROR", "Mã giao dịch dài 3–100 ký tự", "bankTransferReference")
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(server, events)
        vm.submit()
        advanceUntilIdle()
        assertEquals("Mã giao dịch dài 3–100 ký tự", vm.state.value.referenceServerError)
        assertTrue(events.isEmpty())
        assertEquals(1, server.loads)
        assertTrue(vm.state.value.canSubmit) // the customer may correct it and send again

        vm.setReference("FT2611190002")
        assertNull(vm.state.value.referenceServerError)
    }

    @Test
    fun `a 400 on the receipt asks for the photo again`() = runTest(dispatcher) {
        val server = Server().apply {
            wallets += ApiResult.Success(Wallet(debtBalance = 20_000, maxDebt = 100_000))
            debts += ApiResult.Success(listOf(fee))
            declarations += ApiResult.Failure("VALIDATION_ERROR", "Ảnh không hợp lệ", "evidenceUrl")
        }
        val vm = viewModel(server, mutableListOf())
        vm.submit()
        advanceUntilIdle()
        assertEquals(EvidenceStatus.FAILED, vm.state.value.evidence?.status)
        assertEquals("Ảnh không hợp lệ", vm.state.value.evidence?.error)
        assertNull(vm.state.value.referenceServerError)
        assertFalse(vm.state.value.canSubmit)
    }

    private fun owing() = Server().apply {
        wallets += ApiResult.Success(Wallet(debtBalance = 20_000, maxDebt = 100_000))
        debts += ApiResult.Success(listOf(fee))
    }

    @Test
    fun `the transfer card shows the account of 107 and the note with the phone of 9`() = runTest(dispatcher) {
        val vm = viewModel(owing(), mutableListOf(), config = FakeServerConfigRepository(ApiResult.Success(serverConfig(bank = bank))))
        assertEquals(TransferCard("Vietcombank", "0123456789", "CONG TY SERVIO", "SERVIO 0912345678"), vm.state.value.transfer)
    }

    @Test
    fun `no card while AW-15 has no account, no note without the phone`() = runTest(dispatcher) {
        // The seed: transferNoteTemplate only.
        assertNull(viewModel(owing(), mutableListOf()).state.value.transfer)
        // 107 failed and the session has no config yet.
        val offline = FakeServerConfigRepository(ApiResult.Failure("NETWORK_ERROR", "Mất kết nối"))
        assertNull(viewModel(owing(), mutableListOf(), config = offline).state.value.transfer)
        // 9 failed: the card stays, without its note row.
        val vm = viewModel(
            owing(),
            mutableListOf(),
            config = FakeServerConfigRepository(ApiResult.Success(serverConfig(bank = bank))),
            account = Account(ApiResult.Failure("NETWORK_ERROR", "Mất kết nối")),
        )
        assertEquals("0123456789", vm.state.value.transfer?.accountNumber)
        assertNull(vm.state.value.transfer?.note)
    }

    @Test
    fun `reference and uploaded receipt survive a recreated ViewModel`() = runTest(dispatcher) {
        val server = Server().apply {
            wallets += ApiResult.Success(Wallet(debtBalance = 20_000, maxDebt = 100_000))
            debts += ApiResult.Success(listOf(fee))
        }
        val handle = SavedStateHandle()
        val first = viewModel(server, mutableListOf(), handle)
        first.setReference("FT2611190001")
        handle["evidenceUri"] = "content://media/picker/1" // what pickEvidence saves (needs android.net.Uri)
        handle["evidenceUrl"] = "/api/v1/files/f1"

        val second = viewModel(server, mutableListOf(), handle)
        assertEquals("FT2611190001", second.state.value.reference)
        assertEquals(EvidenceStatus.UPLOADED, second.state.value.evidence?.status)
        assertTrue(second.state.value.canSubmit)

        // A photo that was still uploading when the process died has to be sent again.
        val third = viewModel(server, mutableListOf(), SavedStateHandle(mapOf("evidenceUri" to "content://media/picker/2")))
        assertEquals(EvidenceStatus.FAILED, third.state.value.evidence?.status)
    }
}
