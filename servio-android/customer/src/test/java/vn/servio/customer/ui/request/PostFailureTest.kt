package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.core.network.ErrorMessages
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
}
