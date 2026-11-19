package vn.servio.customer.ui.requests

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.R
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.databinding.ItemRequestBinding
import vn.servio.customer.ui.common.RequestStatusChip
import vn.servio.customer.ui.requestdetail.QuoteFormat

class RequestAdapter(
    private val onClick: (ServiceRequestSummary) -> Unit,
) : ListAdapter<RequestCard, RequestAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = getItem(position)
        val request = card.request
        with(holder.binding) {
            val context = root.context
            meta.text = context.getString(R.string.requests_meta, request.code, card.categoryLabel)
            bindChip(chip, card.chip)
            title.text = request.title
            schedule.text = QuoteFormat.schedule(context, request.scheduleType, request.scheduledStartAt, card.scheduledEndAt)
            val last = when (val kind = card.footer) {
                is RequestFooter.SentTo -> context.getString(R.string.requests_sent_to, kind.partners)
                RequestFooter.Expiry -> QuoteFormat.expiry(context, request.expiresAt)
                RequestFooter.None -> null
            }
            footer.isVisible = last != null
            footer.text = last
            root.setOnClickListener { onClick(request) }
        }
    }

    private fun bindChip(view: TextView, chip: RequestChip) {
        val context = view.context
        when (chip) {
            is RequestChip.Status -> RequestStatusChip.bind(view, chip.status)
            is RequestChip.Quotes -> {
                view.text = context.getString(R.string.requests_quotes, chip.count)
                view.setBackgroundResource(R.drawable.bg_status_chip)
                view.setTextColor(context.getColor(vn.servio.core.R.color.servio_text_accent))
                TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(view, R.drawable.ic_quote_accent, 0, 0, 0)
            }
            RequestChip.WaitingForQuotes -> {
                view.setText(R.string.requests_waiting_quotes)
                view.setBackgroundResource(R.drawable.bg_skill_chip)
                view.setTextColor(context.getColor(vn.servio.core.R.color.servio_text_primary))
                TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(view, R.drawable.ic_clock_small, 0, 0, 0)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RequestCard>() {
        override fun areItemsTheSame(old: RequestCard, new: RequestCard) = old.request.id == new.request.id
        override fun areContentsTheSame(old: RequestCard, new: RequestCard) = old == new
    }
}
