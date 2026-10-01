package vn.servio.customer.ui.requests

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.R
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.databinding.ItemRequestBinding

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
            meta.text = "${request.categoryName} · ${request.code}"
            address.text = request.addressSnapshot
            quotes.text = root.context.getString(R.string.requests_quotes, request.quoteCount)
            root.setOnClickListener { onClick(request) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ServiceRequestSummary>() {
        override fun areItemsTheSame(old: ServiceRequestSummary, new: ServiceRequestSummary) = old.id == new.id
        override fun areContentsTheSame(old: ServiceRequestSummary, new: ServiceRequestSummary) = old == new
    }
}
