package vn.servio.customer.ui.category

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.core.data.model.Category
import vn.servio.customer.databinding.ItemServiceBinding
import vn.servio.customer.ui.category.CategoryListViewModel.ServiceRow

/** CS-07 right column: level-2 services, the only rows that lead on to CS-08 (#36 takes a level-2 id). */
class ServiceAdapter(
    private val onClick: (Category) -> Unit,
) : ListAdapter<ServiceRow, ServiceAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemServiceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemServiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        with(holder.binding) {
            name.text = row.service.name
            group.isVisible = row.group != null
            group.text = row.group?.name
            root.setOnClickListener { onClick(row.service) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ServiceRow>() {
        override fun areItemsTheSame(old: ServiceRow, new: ServiceRow) = old.service.id == new.service.id
        override fun areContentsTheSame(old: ServiceRow, new: ServiceRow) = old == new
    }
}
