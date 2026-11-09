package vn.servio.customer.ui.request

import android.content.res.ColorStateList
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import vn.servio.core.R as CoreR
import vn.servio.customer.R
import vn.servio.customer.databinding.ViewCreateRequestStepsBinding

/** Fills the step bar included by CS-08 → CS-10 (layout view_create_request_steps). */
object CreateRequestSteps {

    const val COUNT = 3

    /** [step] is 1-based; segments up to it are filled. */
    fun bind(binding: ViewCreateRequestStepsBinding, step: Int, @StringRes label: Int) {
        val context = binding.root.context
        val done = ColorStateList.valueOf(ContextCompat.getColor(context, CoreR.color.servio_text_primary))
        val todo = ColorStateList.valueOf(ContextCompat.getColor(context, CoreR.color.servio_divider))
        listOf(binding.segment1, binding.segment2, binding.segment3).forEachIndexed { index, segment ->
            segment.backgroundTintList = if (index < step) done else todo
        }
        binding.stepLabel.text = context.getString(R.string.create_request_step, step, COUNT, context.getString(label))
    }
}
