package vn.servio.customer.ui.requests

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.R
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.databinding.ItemRequestBinding

/** The label on a CS-14 card: the quote count while the request is open, otherwise its status. */
sealed interface RequestChip {
    data object WaitingForQuotes : RequestChip
    data class Quotes(val count: Int) : RequestChip
    data class Status(val status: ServiceRequestStatus) : RequestChip

    companion object {
        fun of(status: ServiceRequestStatus, quoteCount: Int): RequestChip = when {
            status != ServiceRequestStatus.OPEN -> Status(status)
            quoteCount > 0 -> Quotes(quoteCount)
            else -> WaitingForQuotes
        }
    }
}

class RequestAdapter(
    private val onClick: (ServiceRequestSummary) -> Unit,
) : ListAdapter<ServiceRequestSummary, RequestAdapter.ViewHolder>(Diff) {

    class ViewHolder(val binding: ItemRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = getItem(position)
        with(holder.binding) {
            title.text = request.title
            meta.text = root.context.getString(R.string.requests_meta, request.categoryName, request.code)
            address.text = request.addressSnapshot
            quotes.text = chipText(root.context, RequestChip.of(request.status, request.quoteCount))
            root.setOnClickListener { onClick(request) }
        }
    }

    private fun chipText(context: Context, chip: RequestChip): String = when (chip) {
        RequestChip.WaitingForQuotes -> context.getString(R.string.requests_waiting_quotes)
        is RequestChip.Quotes -> context.getString(R.string.requests_quotes, chip.count)
        is RequestChip.Status -> context.getString(
            when (chip.status) {
                ServiceRequestStatus.DRAFT -> R.string.requests_status_draft
                ServiceRequestStatus.OPEN -> R.string.requests_status_open
                ServiceRequestStatus.MATCHED -> R.string.requests_status_matched
                ServiceRequestStatus.EXPIRED -> R.string.requests_status_expired
                ServiceRequestStatus.CANCELLED -> R.string.requests_status_cancelled
                ServiceRequestStatus.REJECTED_BY_MODERATION -> R.string.requests_status_rejected
            },
        )
    }

    private object Diff : DiffUtil.ItemCallback<ServiceRequestSummary>() {
        override fun areItemsTheSame(old: ServiceRequestSummary, new: ServiceRequestSummary) = old.id == new.id
        override fun areContentsTheSame(old: ServiceRequestSummary, new: ServiceRequestSummary) = old == new
    }
}
