package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.core.network.ErrorMessages
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.ui.request.CreateRequestViewModel.Step
import vn.servio.customer.ui.request.PostFailure.BackToStep
import vn.servio.customer.ui.request.PostFailure.Dialog
import vn.servio.customer.ui.request.PostFailure.DialogKind
import vn.servio.customer.ui.request.PostFailure.Message
import vn.servio.customer.ui.request.PostFailure.StepNotice

class PostFailureTest {

    @Test
    fun contactInfoGoesBackToDescribe() {
        assertEquals(
            BackToStep(Step.DESCRIBE, StepNotice.CONTACT_INFO, "srv"),
            PostFailure.of("CONTACT_INFO_NOT_ALLOWED", "description", "srv"),
        )
    }

    @Test
    fun fileNotReadyGoesBackToDescribe() {
        assertEquals(
            BackToStep(Step.DESCRIBE, StepNotice.IMAGES_NOT_READY, "srv"),
            PostFailure.of("FILE_NOT_READY", "images[2].url", "srv"),
        )
    }

    @Test
    fun accountProblemsOpenADialogWithTheServerMessage() {
        val debt = "Bạn còn 150.000 đ phí huỷ chưa thanh toán, hãy thanh toán để đăng yêu cầu mới"
        assertEquals(Dialog(DialogKind.OUTSTANDING_DEBT, debt), PostFailure.of("OUTSTANDING_DEBT", null, debt))
        val restricted = "Bạn huỷ bài quá nhiều lần, có thể đăng lại sau 14:00 05/11"
        assertEquals(Dialog(DialogKind.POSTING_RESTRICTED, restricted), PostFailure.of("POSTING_RESTRICTED", null, restricted))
        assertEquals(
            Dialog(DialogKind.PROFILE_INCOMPLETE, "Hãy cập nhật họ tên"),
            PostFailure.of("PROFILE_INCOMPLETE", null, "Hãy cập nhật họ tên"),
        )
    }

    @Test
    fun validationErrorGoesToTheStepOfItsField() {
        assertEquals(BackToStep(Step.ADDRESS_TIME, StepNotice.SERVER, "m"), PostFailure.of("VALIDATION_ERROR", "scheduledStartAt", "m"))
        assertEquals(BackToStep(Step.ADDRESS_TIME, StepNotice.SERVER, "m"), PostFailure.of("VALIDATION_ERROR", "addressId", "m"))
        assertEquals(BackToStep(Step.BUDGET, StepNotice.SERVER, "m"), PostFailure.of("VALIDATION_ERROR", "budgetMin", "m"))
        assertEquals(BackToStep(Step.BUDGET, StepNotice.SERVER, "m"), PostFailure.of("VALIDATION_ERROR", "searchRadiusKm", "m"))
        assertEquals(BackToStep(Step.DESCRIBE, StepNotice.SERVER, "m"), PostFailure.of("VALIDATION_ERROR", "title", "m"))
        assertEquals(BackToStep(Step.DESCRIBE, StepNotice.SERVER, "m"), PostFailure.of("VALIDATION_ERROR", "images", "m"))
    }

    @Test
    fun validationErrorWithoutAKnownFieldStaysWithASnackbar() {
        assertEquals(Message("m"), PostFailure.of("VALIDATION_ERROR", "somethingElse", "m"))
        assertEquals(Message("m"), PostFailure.of("VALIDATION_ERROR", null, "m"))
    }

    @Test
    fun networkAndOtherErrorsStayWithASnackbar() {
        assertEquals(Message("offline"), PostFailure.of(ErrorMessages.NETWORK_ERROR, null, "offline"))
        assertEquals(Message("x"), PostFailure.of(ErrorMessages.UNKNOWN, null, "x"))
        // A field on another code does not send the customer back.
        assertEquals(Message("x"), PostFailure.of("NOT_FOUND", "addressId", "x"))
    }

    @Test
    fun stepOfFields() {
        assertEquals(Step.DESCRIBE, PostFailure.stepOf("serviceCategoryId"))
        assertEquals(Step.DESCRIBE, PostFailure.stepOf("images[0].url"))
        assertEquals(Step.ADDRESS_TIME, PostFailure.stepOf("scheduleType"))
        assertEquals(Step.BUDGET, PostFailure.stepOf("requireMinRating"))
        assertNull(PostFailure.stepOf("phone"))
        assertNull(PostFailure.stepOf(null))
    }

    @Test
    fun `OUTSTANDING_DEBT is the fee dialog with the server's sentence`() {
        val debt = "Bạn còn 150.000 đ phí huỷ chưa thanh toán, hãy thanh toán để đăng yêu cầu mới"
        val failure = PostFailure.of("OUTSTANDING_DEBT", null, debt)
        assertEquals(Dialog(DialogKind.OUTSTANDING_DEBT, debt), failure)
        assertNull((failure as Dialog).orderId)
    }

    @Test
    fun `UNPAID_ORDER is its own dialog with the server's sentence`() {
        val unpaid = "Đơn OD2611190016 chưa thanh toán cho đối tác, hãy thanh toán để đăng yêu cầu mới"
        assertEquals(Dialog(DialogKind.UNPAID_ORDER, unpaid), PostFailure.of("UNPAID_ORDER", null, unpaid))
    }

    private fun order(id: String, status: OrderStatus) = OrderSummary(id = id, code = "OD-$id", status = status)

    @Test
    fun `Xem don opens the only order waiting for payment`() {
        val active = listOf(order("o1", OrderStatus.ON_THE_WAY), order("o2", OrderStatus.AWAITING_PAYMENT), order("o3", OrderStatus.DISPUTED))
        assertEquals("o2", PostFailure.unpaidOrderId(active, complete = true))
    }

    @Test
    fun `Xem don opens CS-19 when there is no single order to open`() {
        // None among the active orders (paid meanwhile, or the server sees one the list does not).
        assertNull(PostFailure.unpaidOrderId(listOf(order("o1", OrderStatus.ARRIVED)), complete = true))
        assertNull(PostFailure.unpaidOrderId(emptyList(), complete = true))
        // More than one: the customer picks on CS-19.
        val two = listOf(order("o1", OrderStatus.AWAITING_PAYMENT), order("o2", OrderStatus.AWAITING_PAYMENT))
        assertNull(PostFailure.unpaidOrderId(two, complete = true))
        // Only the first page was read: another one may be on the next.
        assertNull(PostFailure.unpaidOrderId(listOf(order("o1", OrderStatus.AWAITING_PAYMENT)), complete = false))
    }

    @Test
    fun `CONSENT_REQUIRED opens the consent screen instead of a message`() {
        val needs = "Bạn cần đọc và đồng ý Điều khoản sử dụng và Chính sách bảo mật"
        assertEquals(PostFailure.NeedsConsent, PostFailure.of("CONSENT_REQUIRED", null, needs))
    }
}
