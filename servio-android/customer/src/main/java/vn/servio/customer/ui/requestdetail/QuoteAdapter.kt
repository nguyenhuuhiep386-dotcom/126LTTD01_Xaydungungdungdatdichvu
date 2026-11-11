package vn.servio.customer.ui.requestdetail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuotePartner
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.databinding.ItemQuoteBinding
import vn.servio.customer.databinding.ViewQuotePartnerBinding

/** The partner block shared by the CS-15 card and the CS-16 sheet. The initial stays visible behind a missing avatar. */
fun ViewQuotePartnerBinding.bind(partner: QuotePartner, images: RemoteImages) {
    val context = root.context
    avatarInitial.text = QuoteTexts.initial(partner.fullName)
    images.load(partner.avatarUrl, avatarImage)
    nameText.text = partner.fullName
    ratingText.text = QuoteFormat.rating(context, partner)
    statsText.text = QuoteFormat.stats(context, partner)
}

/** CS-15 quote cards. [Row.selectable] decides whether "Chọn" is offered (see RequestDetailViewModel.State.canSelect). */
class QuoteAdapter(
    private val images: RemoteImages,
    private val onOpen: (CustomerQuote) -> Unit,
    private val onSelect: (CustomerQuote) -> Unit,
) : ListAdapter<QuoteAdapter.Row, QuoteAdapter.ViewHolder>(Diff) {

    data class Row(val quote: CustomerQuote, val selectable: Boolean)

    class ViewHolder(val binding: ItemQuoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemQuoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (quote, selectable) = getItem(position)
        with(holder.binding) {
            val context = root.context
            partner.bind(quote.partner, images)
            priceText.text = QuoteFormat.price(context, quote.amount)
            availableText.text = QuoteFormat.available(context, quote)
            noteText.text = quote.note
            noteText.isVisible = !quote.note.isNullOrBlank()
            acceptedText.isVisible = quote.status == QuoteStatus.ACCEPTED
            selectButton.isVisible = selectable
            selectButton.setOnClickListener { onSelect(quote) }
            root.setOnClickListener { onOpen(quote) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = old.quote.id == new.quote.id
        override fun areContentsTheSame(old: Row, new: Row) = old == new
    }
}
