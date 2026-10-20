package vn.servio.customer.ui.home

import android.graphics.drawable.InsetDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.core.data.model.Category
import vn.servio.customer.R
import vn.servio.customer.databinding.ItemCategoryBinding
import vn.servio.customer.ui.common.CategoryIcons

class CategoryAdapter(
    private val onClick: (Category) -> Unit,
) : ListAdapter<Category, CategoryAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = getItem(position)
        with(holder.binding) {
            name.text = category.name
            // The 40dp round mark keeps its layout; the 24dp icon sits in it, inset 8dp on each side.
            initial.text = null
            initial.foreground = InsetDrawable(
                AppCompatResources.getDrawable(root.context, CategoryIcons.of(category)),
                root.resources.getDimensionPixelSize(vn.servio.core.R.dimen.space_s),
            )
            services.text = root.context.getString(R.string.home_services_count, category.children.size)
            root.setOnClickListener { onClick(category) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Category>() {
        override fun areItemsTheSame(old: Category, new: Category) = old.id == new.id
        override fun areContentsTheSame(old: Category, new: Category) = old == new
    }
}
