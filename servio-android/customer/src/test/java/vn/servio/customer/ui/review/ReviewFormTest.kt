package vn.servio.customer.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.PendingReview
import vn.servio.customer.data.model.ReviewBody
import vn.servio.customer.ui.orderdetail.OrderDetailUi
import vn.servio.customer.ui.review.ReviewForm.Criterion
import vn.servio.customer.ui.review.ReviewForm.Failure

class ReviewFormTest {

    @Test
    fun `send is off until a star is chosen and while sending`() {
        assertFalse(ReviewForm.canSubmit(0, sending = false))
        assertTrue(ReviewForm.canSubmit(1, sending = false))
        assertTrue(ReviewForm.canSubmit(5, sending = false))
        assertFalse(ReviewForm.canSubmit(5, sending = true))
    }

    @Test
    fun `each star count has its label, none asks to tap`() {
        assertEquals(R.string.review_rating_none, ReviewForm.ratingLabel(0))
        assertEquals(
            listOf(R.string.review_rating_1, R.string.review_rating_2, R.string.review_rating_3, R.string.review_rating_4, R.string.review_rating_5),
            (1..5).map(ReviewForm::ratingLabel),
        )
    }

    @Test
    fun `the comment is trimmed, empty becomes null, never more than 500`() {
        assertNull(ReviewForm.commentToSend(null))
        assertNull(ReviewForm.commentToSend("   \n "))
        assertEquals("Làm nhanh, sạch sẽ", ReviewForm.commentToSend("  Làm nhanh, sạch sẽ \n"))
        assertEquals(500, ReviewForm.commentToSend("a".repeat(600))?.length)
        assertEquals(500, ReviewForm.MAX_COMMENT)
    }

    @Test
    fun `the body leaves out criteria not chosen and sends no tags`() {
        val body = ReviewForm.body("a1", 5, mapOf(Criterion.PUNCTUALITY to 5, Criterion.QUALITY to 4, Criterion.PRICE to 0), " ")
        assertEquals(ReviewBody("a1", 5, punctualityRating = 5, qualityRating = 4, attitudeRating = null, priceRating = null, comment = null), body)
    }

    @Test
    fun `each 95 error code has its outcome`() {
        assertEquals(Failure.WINDOW_CLOSED, ReviewForm.failure("REVIEW_WINDOW_CLOSED"))
        assertEquals(Failure.ALREADY_SUBMITTED, ReviewForm.failure("REVIEW_ALREADY_SUBMITTED"))
        assertEquals(Failure.OTHER, ReviewForm.failure("INVALID_STATUS_TRANSITION"))
        assertEquals(Failure.OTHER, ReviewForm.failure("NETWORK_ERROR"))
    }

    private val pending = listOf(PendingReview("01A1-O", "01A1-A", "OD1", "Bảo", "2026-11-18T08:00:00Z", "2026-11-23T08:00:00Z"))

    private fun order(status: OrderStatus, assignment: String = "01a1-a") =
        OrderDetail(id = "01a1-o", code = "OD1", status = status, assignments = listOf(OrderAssignment(assignment, status, isPrimary = true)))

    @Test
    fun `the review button shows on a completed order still in 100`() {
        assertTrue(OrderDetailUi.canReview(order(OrderStatus.COMPLETED), pending))
        assertFalse(OrderDetailUi.canReview(order(OrderStatus.COMPLETED), emptyList())) // reviewed or past the deadline
        assertFalse(OrderDetailUi.canReview(order(OrderStatus.COMPLETED), null)) // 100 failed or not answered
        assertFalse(OrderDetailUi.canReview(order(OrderStatus.COMPLETED, assignment = "other"), pending))
        assertFalse(OrderDetailUi.canReview(order(OrderStatus.COMPLETED_BY_PARTNER), pending))
    }
}
