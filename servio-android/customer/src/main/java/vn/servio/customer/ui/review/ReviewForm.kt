package vn.servio.customer.ui.review

import androidx.annotation.StringRes
import vn.servio.customer.R
import vn.servio.customer.data.model.PendingReview
import vn.servio.customer.data.model.ReviewBody

/** Rules of the CS-26 form (pure, unit tested). */
object ReviewForm {

    /** #95 keeps up to 500 characters of comment. */
    const val MAX_COMMENT = 500

    const val MAX_STARS = 5

    /** The four optional criteria of #95, in the order of Figma. */
    enum class Criterion { PUNCTUALITY, QUALITY, ATTITUDE, PRICE }

    /** Label under the big stars; 0 = nothing chosen yet. */
    @StringRes
    fun ratingLabel(rating: Int): Int = when (rating) {
        1 -> R.string.review_rating_1
        2 -> R.string.review_rating_2
        3 -> R.string.review_rating_3
        4 -> R.string.review_rating_4
        5 -> R.string.review_rating_5
        else -> R.string.review_rating_none
    }

    fun canSubmit(rating: Int, sending: Boolean): Boolean = rating in 1..MAX_STARS && !sending

    /** Trimmed comment, null when nothing is left (the server then stores none). */
    fun commentToSend(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_COMMENT)

    /** #95 body; a criterion not chosen (0 or missing) is left out. */
    fun body(assignmentId: String, rating: Int, criteria: Map<Criterion, Int>, comment: String?): ReviewBody {
        fun pick(criterion: Criterion) = criteria[criterion]?.takeIf { it in 1..MAX_STARS }
        return ReviewBody(
            assignmentId = assignmentId,
            rating = rating,
            punctualityRating = pick(Criterion.PUNCTUALITY),
            qualityRating = pick(Criterion.QUALITY),
            attitudeRating = pick(Criterion.ATTITUDE),
            priceRating = pick(Criterion.PRICE),
            comment = commentToSend(comment),
        )
    }

    /** The #100 row of this assignment; null = already reviewed or past the deadline. GUIDs compared without case. */
    fun pendingFor(pending: List<PendingReview>, orderId: String, assignmentId: String?): PendingReview? =
        pending.firstOrNull {
            it.orderId.equals(orderId, ignoreCase = true) && assignmentId != null && it.assignmentId.equals(assignmentId, ignoreCase = true)
        }

    /** What a failed #95 means. */
    enum class Failure {
        /** 422 REVIEW_WINDOW_CLOSED: say so; the form is closed. */
        WINDOW_CLOSED,

        /** 409 REVIEW_ALREADY_SUBMITTED: say so and go back. */
        ALREADY_SUBMITTED,

        /** Anything else: keep the form as it is, with the message. */
        OTHER,
    }

    fun failure(code: String): Failure = when (code) {
        "REVIEW_WINDOW_CLOSED" -> Failure.WINDOW_CLOSED
        "REVIEW_ALREADY_SUBMITTED" -> Failure.ALREADY_SUBMITTED
        else -> Failure.OTHER
    }
}
