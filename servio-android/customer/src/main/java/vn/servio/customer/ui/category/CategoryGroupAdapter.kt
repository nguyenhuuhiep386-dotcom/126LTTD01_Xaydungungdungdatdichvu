package vn.servio.customer.ui.category

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.core.data.model.Category
import vn.servio.customer.databinding.ItemCategoryGroupBinding
import vn.servio.customer.ui.common.CategoryIcons

/** CS-07 left column: level-1 groups. */
class CategoryGroupAdapter(
    private val onClick: (Category) -> Unit,
) : ListAdapter<CategoryGroupAdapter.Item, CategoryGroupAdapter.ViewHolder>(Diff) {

    data class Item(val group: Category, val selected: Boolean)

    class ViewHolder(val binding: ItemCategoryGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCategoryGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            icon.setImageResource(CategoryIcons.of(item.group))
            name.text = item.group.name
            name.typeface = if (item.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            root.isSelected = item.selected
            root.setOnClickListener { onClick(item.group) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(old: Item, new: Item) = old.group.id == new.group.id
        override fun areContentsTheSame(old: Item, new: Item) = old == new
    }
}
