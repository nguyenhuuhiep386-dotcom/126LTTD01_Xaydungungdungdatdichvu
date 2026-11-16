package vn.servio.customer.ui.requestdetail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.R
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuotePartner
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.databinding.ItemQuoteBinding
import vn.servio.customer.databinding.ViewQuoteRatingBinding
import vn.servio.customer.ui.common.RemoteImages

/** "★ 4,8 (32 đánh giá)", or only "Chưa có đánh giá" for a partner without reviews. */
fun ViewQuoteRatingBinding.bind(partner: QuotePartner) {
    val score = QuoteFormat.score(partner)
    star.isVisible = score != null
    this.score.isVisible = score != null
    this.score.text = score
    count.text = if (score != null) {
        root.context.getString(R.string.quote_review_count, partner.totalReviews)
    } else {
        root.context.getString(R.string.quote_no_reviews)
    }
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
            val partner = quote.partner
            // The initials stay visible behind a missing avatar.
            avatarInitials.text = QuoteTexts.initials(partner.fullName)
            images.load(partner.avatarUrl, avatarImage)
            nameText.text = partner.fullName
            experienceText.text = context.getString(R.string.quote_experience, partner.yearsOfExperience)
            rating.bind(partner)
            priceText.text = QuoteFormat.money(context, quote.amount)
            availableText.text = context.getString(
                R.string.quote_available,
                QuoteFormat.moment(context, quote.availableFrom),
                QuoteFormat.duration(context, quote.estimatedDurationMinutes),
            )
            noteText.isVisible = !quote.note.isNullOrBlank()
            noteText.text = context.getString(R.string.quote_note, quote.note.orEmpty().trim())
            acceptedText.isVisible = quote.status == QuoteStatus.ACCEPTED
            actions.isVisible = selectable
            selectButton.setOnClickListener { onSelect(quote) }
            root.setOnClickListener { onOpen(quote) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = old.quote.id == new.quote.id
        override fun areContentsTheSame(old: Row, new: Row) = old == new
    }
}
