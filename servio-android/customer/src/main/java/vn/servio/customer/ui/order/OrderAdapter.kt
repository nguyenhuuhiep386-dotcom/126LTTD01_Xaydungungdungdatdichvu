package vn.servio.customer.ui.order

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.databinding.ItemOrderBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.requestdetail.QuoteFormat
import vn.servio.customer.ui.requestdetail.QuoteTexts

/** CS-19 cards. Every order opens CS-20 (also "Chờ bạn xác nhận" in KH-5; CS-23 comes with KH-6). */
class OrderAdapter(
    private val images: RemoteImages,
    private val onOpen: (OrderSummary) -> Unit,
) : ListAdapter<OrderSummary, OrderAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val order = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            code.text = context.getString(R.string.order_code, order.code)
            OrderStatusChip.bind(chip, order.status)
            title.text = order.title
            val partner = order.counterpart
            partnerName.text = partner.displayName
            avatarInitials.text = QuoteTexts.initials(partner.displayName)
            // Loading again would restart a download that is still running, so only when the url changes.
            if (avatarImage.getTag(R.id.avatarImage) != partner.avatarUrl) {
                avatarImage.setTag(R.id.avatarImage, partner.avatarUrl)
                images.load(partner.avatarUrl, avatarImage)
            }
            val schedule = OrderTexts.dayTime(order.scheduledStartAt, QuoteFormat.weekdays(context))
            this.schedule.text = schedule
            this.schedule.visibility = if (schedule == null) View.INVISIBLE else View.VISIBLE
            amount.text = OrderFormat.money(context, order.totalAmount)
            root.setOnClickListener { onOpen(order) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<OrderSummary>() {
        override fun areItemsTheSame(oldItem: OrderSummary, newItem: OrderSummary) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: OrderSummary, newItem: OrderSummary) = oldItem == newItem
    }
}
