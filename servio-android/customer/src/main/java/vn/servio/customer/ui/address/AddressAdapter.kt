package vn.servio.customer.ui.address

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.core.R as CoreR
import vn.servio.customer.data.model.Address
import vn.servio.customer.databinding.ItemAddressBinding

class AddressAdapter(
    private val selectMode: Boolean,
    private val onClick: (Address) -> Unit,
    private val onEdit: (Address) -> Unit,
    private val onLongClick: (View, Address) -> Unit,
) : ListAdapter<AddressAdapter.Item, AddressAdapter.ViewHolder>(Diff) {

    data class Item(val address: Address, val isSelected: Boolean)

    class ViewHolder(val binding: ItemAddressBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAddressBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val address = item.address
        with(holder.binding) {
            val context = root.context
            label.text = address.label
            defaultChip.isVisible = address.isDefault
            addressLine.text = address.fullAddress
            radio.isVisible = selectMode
            radio.isChecked = item.isSelected
            root.strokeColor = ContextCompat.getColor(
                context, if (item.isSelected) CoreR.color.servio_text_accent else CoreR.color.servio_divider,
            )
            root.setCardBackgroundColor(
                ContextCompat.getColor(context, if (item.isSelected) CoreR.color.servio_primary_container else CoreR.color.servio_surface),
            )
            root.setOnClickListener { onClick(address) }
            root.setOnLongClickListener {
                onLongClick(it, address)
                true
            }
            editButton.setOnClickListener { onEdit(address) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(old: Item, new: Item) = old.address.id == new.address.id
        override fun areContentsTheSame(old: Item, new: Item) = old == new
    }
}
