package vn.servio.partner.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.core.utils.Money
import vn.servio.partner.R
import vn.servio.partner.data.model.FeedItem
import vn.servio.partner.databinding.ItemFeedBinding

class FeedAdapter(
    private val onClick: (FeedItem) -> Unit,
) : ListAdapter<FeedItem, FeedAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemFeedBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        with(holder.binding) {
            category.text = item.categoryName
            title.text = item.title
            area.text = item.areaLabel
            distance.text = context.getString(R.string.feed_distance, item.distanceKm)
            val budgetText = Money.range(item.budgetMin, item.budgetMax)
            budget.isVisible = budgetText != null
            budget.text = budgetText?.let { context.getString(R.string.feed_budget, it) }
            quotes.text = context.getString(R.string.feed_quotes, item.quoteCount)
            root.setOnClickListener { onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FeedItem>() {
        override fun areItemsTheSame(old: FeedItem, new: FeedItem) = old.requestId == new.requestId
        override fun areContentsTheSame(old: FeedItem, new: FeedItem) = old == new
    }
}
