package vn.servio.customer.ui.common

import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.chip.ChipGroup
import vn.servio.customer.R

/** Skill names as rounded labels that wrap to the next line (CS-16, CS-17). Replaces what the group showed. */
fun ChipGroup.showSkills(skills: List<String>) {
    removeAllViews()
    val horizontal = resources.getDimensionPixelSize(vn.servio.core.R.dimen.space_m)
    val vertical = resources.getDimensionPixelSize(vn.servio.core.R.dimen.space_xs)
    skills.forEach { skill ->
        addView(
            TextView(context).apply {
                text = skill
                TextViewCompat.setTextAppearance(this, vn.servio.core.R.style.TextAppearance_Servio_Caption)
                setTextColor(context.getColor(vn.servio.core.R.color.servio_text_primary))
                setBackgroundResource(R.drawable.bg_skill_chip)
                setPadding(horizontal, vertical, horizontal, vertical)
            },
        )
    }
}
