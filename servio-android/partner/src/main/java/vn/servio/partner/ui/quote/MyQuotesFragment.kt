package vn.servio.partner.ui.quote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import vn.servio.core.ui.chat.ChatFragment
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.model.PartnerQuote
import vn.servio.partner.data.model.QuoteStatus
import vn.servio.partner.databinding.FragmentMyQuotesBinding
import vn.servio.partner.databinding.ItemMyQuoteBinding
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.quote.MyQuotesViewModel.Event
import vn.servio.partner.ui.quote.MyQuotesViewModel.Tab

/** PS-12 Báo giá của tôi: tabs Chờ / Được chọn / Đã đóng. */
@AndroidEntryPoint
class MyQuotesFragment : Fragment(R.layout.fragment_my_quotes) {

    private val viewModel: MyQuotesViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMyQuotesBinding.bind(view)
        val adapter = QuoteAdapter(
            onOpen = { quote ->
                findNavController().navigate(R.id.ps10, bundleOf(RequestDetailFragment.ARG_REQUEST_ID to quote.serviceRequestId))
            },
            onWithdraw = { quote -> confirmWithdraw(quote) },
            onChat = { quote ->
                quote.conversationId?.let {
                    findNavController().navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to it))
                }
            },
        )
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(showSpinner = false) }
        binding.retryButton.setOnClickListener { viewModel.load() }
        listOf(R.string.quotes_tab_pending, R.string.quotes_tab_accepted, R.string.quotes_tab_closed).forEach {
            binding.tabs.addTab(binding.tabs.newTab().setText(it))
        }
        binding.tabs.selectTab(binding.tabs.getTabAt(viewModel.tab.value.ordinal))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = viewModel.selectTab(Tab.entries[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        collectWhenStarted(viewModel.state.combine(viewModel.tab) { state, tab -> state to tab }) { (state, tab) ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            val items = (state as? UiState.Content)?.data.orEmpty().filter { it.status in tab.statuses }
            binding.messageGroup.isVisible = state is UiState.Error || (state is UiState.Content && items.isEmpty())
            binding.retryButton.isVisible = state is UiState.Error
            binding.message.text = when (state) {
                is UiState.Error -> state.message
                else -> getString(R.string.quotes_empty)
            }
            adapter.submitList(items)
            val pending = (state as? UiState.Content)?.data.orEmpty().count { it.status == QuoteStatus.PENDING }
            binding.tabs.getTabAt(0)?.text = getString(R.string.quotes_tab_pending_count, pending)
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Message -> Snackbar.make(view, event.text, Snackbar.LENGTH_LONG).show()
                Event.Withdrawn -> Snackbar.make(view, R.string.quote_withdrawn, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmWithdraw(quote: PartnerQuote) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.quote_withdraw_title)
            .setMessage(R.string.quote_withdraw_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.quote_withdraw) { _, _ -> viewModel.withdraw(quote.id) }
            .show()
    }

    private class QuoteAdapter(
        private val onOpen: (PartnerQuote) -> Unit,
        private val onWithdraw: (PartnerQuote) -> Unit,
        private val onChat: (PartnerQuote) -> Unit,
    ) : ListAdapter<PartnerQuote, QuoteAdapter.Holder>(Diff) {

        class Holder(val binding: ItemMyQuoteBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemMyQuoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val quote = getItem(position)
            val context = holder.binding.root.context
            with(holder.binding) {
                status.setText(QuoteTexts.status(quote.status))
                sentAt.text = context.getString(R.string.quote_sent_at, Texts.parse(quote.createdAt)?.let(Texts::time).orEmpty())
                title.text = quote.requestTitle
                arrival.text = QuoteTexts.arrival(context, quote)
                amount.text = Money.format(quote.amount)
                withdrawButton.isVisible = quote.status == QuoteStatus.PENDING
                withdrawButton.setOnClickListener { onWithdraw(quote) }
                chatButton.isVisible = quote.conversationId != null
                chatButton.setOnClickListener { onChat(quote) }
                root.setOnClickListener { onOpen(quote) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<PartnerQuote>() {
            override fun areItemsTheSame(old: PartnerQuote, new: PartnerQuote) = old.id == new.id
            override fun areContentsTheSame(old: PartnerQuote, new: PartnerQuote) = old == new
        }
    }
}
