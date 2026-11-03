package vn.servio.customer.ui.request

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.databinding.ItemScheduleChoiceBinding

/**
 * CS-09 day chips (horizontal row) and time slots (2-column grid), CS-10 radius chips. [fillWidth] stretches each
 * item over its grid column. An item that can no longer be booked is faded and not clickable.
 */
class ScheduleChipAdapter(
    private val fillWidth: Boolean,
    private val onClick: (Item) -> Unit,
) : ListAdapter<ScheduleChipAdapter.Item, ScheduleChipAdapter.ViewHolder>(Diff) {

    /** [key] is the ISO date of a day chip, the start hour of a slot or the km of a radius chip. */
    data class Item(val key: String, val label: String, val enabled: Boolean, val selected: Boolean)

    class ViewHolder(val binding: ItemScheduleChoiceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScheduleChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (fillWidth) binding.root.layoutParams = binding.root.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            label.text = item.label
            check.isVisible = item.selected
            // MaterialCardView ignores setChecked while disabled, and a recycled holder may still be: enable it first.
            card.isCheckable = true
            card.isEnabled = true
            card.isChecked = item.selected
            card.isEnabled = item.enabled
            card.alpha = if (item.enabled) 1f else DISABLED_ALPHA
            card.setOnClickListener { if (item.enabled) onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(old: Item, new: Item) = old.key == new.key
        override fun areContentsTheSame(old: Item, new: Item) = old == new
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }
}
