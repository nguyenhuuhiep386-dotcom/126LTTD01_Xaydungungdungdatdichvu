package vn.servio.customer.ui.order

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * "Đơn #mã" on the left and the status chip at the right end, on one line when both fit at their full width;
 * otherwise the chip moves under the code (start-aligned) so the code is never cut. Exactly two children.
 */
class CodeChipRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Space between the two on one line, and between the lines when wrapped. */
    var gap: Int = (8 * resources.displayMetrics.density).toInt()

    private var wrapped = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(childCount == 2) { "CodeChipRow needs a code view and a chip" }
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val loose = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val code = getChildAt(0)
        val chip = getChildAt(1)
        code.measure(loose, unspecified)
        chip.measure(loose, unspecified)
        wrapped = chip.visibility != View.GONE && code.measuredWidth + gap + chip.measuredWidth > width
        val contentHeight = when {
            chip.visibility == View.GONE -> code.measuredHeight
            wrapped -> code.measuredHeight + gap + chip.measuredHeight
            else -> max(code.measuredHeight, chip.measuredHeight)
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val code = getChildAt(0)
        val chip = getChildAt(1)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val inner = r - l - paddingLeft - paddingRight
        fun place(child: View, start: Int, top: Int) {
            val left = if (rtl) paddingLeft + inner - start - child.measuredWidth else paddingLeft + start
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
        if (wrapped || chip.visibility == View.GONE) {
            place(code, 0, paddingTop)
            place(chip, 0, paddingTop + code.measuredHeight + gap)
        } else {
            val line = max(code.measuredHeight, chip.measuredHeight)
            place(code, 0, paddingTop + (line - code.measuredHeight) / 2)
            place(chip, inner - chip.measuredWidth, paddingTop + (line - chip.measuredHeight) / 2)
        }
    }
}
