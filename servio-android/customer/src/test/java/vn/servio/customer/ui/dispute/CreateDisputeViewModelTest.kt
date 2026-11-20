package vn.servio.customer.ui.dispute

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.CreateDisputeBody
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.UploadedFile
import vn.servio.customer.data.repository.FakeDisputeRepository
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.data.repository.FakeServerConfigRepository
import vn.servio.customer.data.repository.FileRepository
import vn.servio.customer.ui.dispute.DisputeRules.DescriptionError
import vn.servio.customer.ui.dispute.DisputeRules.Reason

@OptIn(ExperimentalCoroutinesApi::class)
class CreateDisputeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private object NoFiles : FileRepository {
        override suspend fun uploadPostImage(uri: Uri): ApiResult<UploadedFile> = error("unused")
        override suspend fun uploadEvidence(uri: Uri): ApiResult<UploadedFile> = error("unused")
    }

    private fun vm(disputes: FakeDisputeRepository, config: FakeServerConfigRepository = FakeServerConfigRepository()) =
        CreateDisputeViewModel(
            SavedStateHandle(mapOf(CreateDisputeViewModel.ARG_ORDER_ID to "o1")),
            FakeOrderRepository().apply { order = ApiResult.Success(OrderDetail(id = "o1", code = "OD1", status = OrderStatus.ARRIVED)) },
            config,
            NoFiles,
            disputes,
        )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `nothing is sent until a chip is chosen and the description is valid`() = runTest(dispatcher) {
        val disputes = FakeDisputeRepository()
        val vm = vm(disputes)
        advanceUntilIdle()
        assertFalse(vm.state.value.missingReason) // no error before the first tap

        vm.send()
        advanceUntilIdle()
        assertTrue(vm.state.value.missingReason)
        assertEquals(DescriptionError.EMPTY, vm.state.value.descriptionError)
        assertTrue(disputes.created.isEmpty())

        vm.selectReason(Reason.OVERCHARGE)
        vm.setDescription("Đòi 0912345678")
        vm.send()
        advanceUntilIdle()
        assertEquals(DescriptionError.CONTACT_INFO, vm.state.value.descriptionError)
        assertTrue(disputes.created.isEmpty())

        vm.setDescription("  Đòi thêm 200.000 đ không báo trước  ")
        vm.send()
        advanceUntilIdle()
        assertEquals(listOf("o1" to CreateDisputeBody("OVERCHARGE", "Đòi thêm 200.000 đ không báo trước", emptyList())), disputes.created)
        assertEquals(CreateDisputeViewModel.Event.Sent, vm.events.first())
    }

    @Test
    fun `a server error on a field goes under it, others become a message`() = runTest(dispatcher) {
        val disputes = FakeDisputeRepository(createAnswer = ApiResult.Failure("VALIDATION_ERROR", "Mô tả dài 10–2000 ký tự", "description"))
        val vm = vm(disputes)
        vm.selectReason(Reason.WORK_QUALITY)
        vm.setDescription("Máy vẫn chảy nước")
        vm.send()
        advanceUntilIdle()
        assertEquals("Mô tả dài 10–2000 ký tự", vm.state.value.serverDescriptionError)
        assertFalse(vm.state.value.sending)
        vm.setDescription("Máy vẫn chảy nước sau 2 giờ")
        assertNull(vm.state.value.serverDescriptionError)

        disputes.createAnswer = ApiResult.Failure("VALIDATION_ERROR", "Lý do không hợp lệ", "reason")
        vm.send()
        advanceUntilIdle()
        assertEquals("Lý do không hợp lệ", vm.state.value.serverReasonError)

        disputes.createAnswer = ApiResult.Failure("DISPUTE_ALREADY_OPEN", "Đơn đang có khiếu nại chưa xử lý xong")
        vm.selectReason(Reason.OTHER)
        vm.send()
        advanceUntilIdle()
        assertEquals(CreateDisputeViewModel.Event.Message("Đơn đang có khiếu nại chưa xử lý xong"), vm.events.first())
    }

    @Test
    fun `order card and info card are hidden when their calls fail`() = runTest(dispatcher) {
        val vm = CreateDisputeViewModel(
            SavedStateHandle(mapOf(CreateDisputeViewModel.ARG_ORDER_ID to "o1")),
            FakeOrderRepository().apply { order = ApiResult.Failure("NETWORK_ERROR", "x") },
            FakeServerConfigRepository(ApiResult.Failure("NETWORK_ERROR", "x")),
            NoFiles,
            FakeDisputeRepository(),
        )
        advanceUntilIdle()
        assertNull(vm.state.value.order)
        assertNull(vm.state.value.config)
        assertTrue(vm.state.value.canSend)
    }
}
