package vn.servio.customer.ui.review

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import vn.servio.customer.R

/**
 * Five tappable stars: up to [rating] the filled ic_star in servio_text_accent (the colour of the score stars of CS-17 /
 * CS-20), the rest the empty ic_star_outline in servio_outline (Figma 18:2704 Icon/star outline). 0 = none chosen.
 * Each star is a 48 dp touch target at least, read as "N sao". Size is set with [setStarSize] (CS-26 uses a big row for
 * the overall score and small rows for the criteria). The state lives in the view model, not here.
 */
class StarRatingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onRatingChanged: ((Int) -> Unit)? = null

    var rating: Int = 0
        set(value) {
            field = value.coerceIn(0, ReviewForm.MAX_STARS)
            paint()
        }

    private val stars: List<ImageView>

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        stars = (1..ReviewForm.MAX_STARS).map { value ->
            ImageView(context).apply {
                setImageResource(R.drawable.ic_star)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = context.getString(R.string.review_star, value)
                setBackgroundResource(android.R.color.transparent)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    rating = value
                    onRatingChanged?.invoke(value)
                }
            }.also { addView(it) }
        }
        setStarSize(SMALL_DP)
    }

    /** [sizeDp]: the drawn star; the touch target grows to 48 dp with padding. */
    fun setStarSize(sizeDp: Int) {
        val density = resources.displayMetrics.density
        val target = maxOf(sizeDp, MIN_TOUCH_DP)
        val padding = ((target - sizeDp) / 2f * density).toInt()
        stars.forEach { star ->
            star.layoutParams = LayoutParams((target * density).toInt(), (target * density).toInt())
            star.setPadding(padding, padding, padding, padding)
        }
    }

    private fun paint() {
        val on = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_text_accent))
        val off = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_outline))
        stars.forEachIndexed { index, star ->
            val chosen = index < rating
            star.setImageResource(if (chosen) R.drawable.ic_star else R.drawable.ic_star_outline)
            star.imageTintList = if (chosen) on else off
            star.isSelected = chosen
        }
    }

    init {
        paint()
    }

    companion object {
        const val SMALL_DP = 24
        const val LARGE_DP = 48
        private const val MIN_TOUCH_DP = 48
    }
}
