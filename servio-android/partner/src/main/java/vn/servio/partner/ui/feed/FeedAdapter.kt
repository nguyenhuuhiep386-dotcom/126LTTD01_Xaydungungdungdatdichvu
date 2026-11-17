package vn.servio.partner.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.core.utils.Money
import vn.servio.partner.R
import vn.servio.partner.data.model.FeedItem
import vn.servio.partner.databinding.ItemFeedBinding
import vn.servio.partner.ui.common.Texts

class FeedAdapter(
    private val onOpen: (FeedItem) -> Unit,
    private val onQuote: (FeedItem) -> Unit,
) : ListAdapter<FeedItem, FeedAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemFeedBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        with(holder.binding) {
            category.text = item.categoryName
            ago.text = Texts.ago(context, item.publishedAt)
            title.text = item.title
            area.text = context.getString(R.string.feed_area, item.areaLabel, Texts.distance(item.distanceKm))
            schedule.text = Texts.schedule(context, item.scheduleType, item.scheduledStartAt, item.scheduledEndAt)
            budget.text = Money.range(item.budgetMin, item.budgetMax) ?: context.getString(R.string.feed_budget_open)
            quotes.text = if (item.quoteCount == 0) {
                context.getString(R.string.feed_no_quotes)
            } else {
                context.getString(R.string.feed_quotes, item.quoteCount)
            }
            quoteButton.isEnabled = !item.hasQuoted
            quoteButton.setText(if (item.hasQuoted) R.string.feed_quoted else R.string.feed_quote)
            quoteButton.setOnClickListener { onQuote(item) }
            root.setOnClickListener { onOpen(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FeedItem>() {
        override fun areItemsTheSame(old: FeedItem, new: FeedItem) = old.requestId == new.requestId
        override fun areContentsTheSame(old: FeedItem, new: FeedItem) = old == new
    }
}
