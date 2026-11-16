package vn.servio.partner.ui.order

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.model.OrderStatus
import vn.servio.partner.data.model.OrderSummary
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.databinding.FragmentOrdersBinding
import vn.servio.partner.databinding.ItemOrderBinding
import vn.servio.partner.ui.common.Texts
import javax.inject.Inject

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val repository: OrderRepository,
) : ViewModel() {

    enum class Tab(val statuses: Set<OrderStatus>) {
        UPCOMING(setOf(OrderStatus.PENDING, OrderStatus.ACCEPTED)),
        ACTIVE(
            setOf(
                OrderStatus.ON_THE_WAY, OrderStatus.ARRIVED, OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED_BY_PARTNER,
                OrderStatus.AWAITING_PAYMENT, OrderStatus.DISPUTED,
            ),
        ),
        DONE(setOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED)),
    }

    private val _state = MutableStateFlow<UiState<List<OrderSummary>>>(UiState.Loading)
    val state: StateFlow<UiState<List<OrderSummary>>> = _state.asStateFlow()

    private val _tab = MutableStateFlow(Tab.UPCOMING)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    init {
        load()
        viewModelScope.launch { repository.changes().collect { load(showSpinner = false) } }
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.list(filter = null).toUiState() }
    }

    fun selectTab(tab: Tab) {
        _tab.value = tab
    }
}

/** PS-13 Đơn hàng: Sắp tới / Đang làm / Hoàn thành, with a banner for orders waiting for confirmation. */
@AndroidEntryPoint
class OrdersFragment : Fragment(R.layout.fragment_orders) {

    private val viewModel: OrdersViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOrdersBinding.bind(view)
        val adapter = OrderAdapter { openOrder(it.id) }
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(showSpinner = false) }
        binding.retryButton.setOnClickListener { viewModel.load() }
        listOf(R.string.orders_tab_upcoming, R.string.orders_tab_active, R.string.orders_tab_done).forEach {
            binding.tabs.addTab(binding.tabs.newTab().setText(it))
        }
        binding.tabs.selectTab(binding.tabs.getTabAt(viewModel.tab.value.ordinal))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = viewModel.selectTab(OrdersViewModel.Tab.entries[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        collectWhenStarted(viewModel.state.combine(viewModel.tab) { state, tab -> state to tab }) { (state, tab) ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            val all = (state as? UiState.Content)?.data.orEmpty()
            val items = all.filter { it.assignmentStatus in tab.statuses }
            binding.messageGroup.isVisible = state is UiState.Error || (state is UiState.Content && items.isEmpty())
            binding.retryButton.isVisible = state is UiState.Error
            binding.message.text = if (state is UiState.Error) state.message else getString(R.string.orders_empty)
            adapter.submitList(items)

            val pending = all.filter { it.assignmentStatus == OrderStatus.PENDING }
            binding.pendingBanner.isVisible = pending.isNotEmpty()
            binding.pendingText.text = getString(R.string.orders_pending_banner, pending.size)
            binding.pendingBanner.setOnClickListener { pending.firstOrNull()?.let { openOrder(it.id) } }
            val upcoming = all.count { it.assignmentStatus in OrdersViewModel.Tab.UPCOMING.statuses }
            binding.tabs.getTabAt(0)?.text = getString(R.string.orders_tab_upcoming_count, upcoming)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load(showSpinner = false)
    }

    private fun openOrder(orderId: String) {
        findNavController().navigate(R.id.ps14, bundleOf(OrderDetailFragment.ARG_ORDER_ID to orderId))
    }

    private class OrderAdapter(private val onOpen: (OrderSummary) -> Unit) :
        ListAdapter<OrderSummary, OrderAdapter.Holder>(Diff) {

        class Holder(val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val order = getItem(position)
            val context = holder.binding.root.context
            with(holder.binding) {
                code.text = context.getString(R.string.order_code, order.code)
                status.setText(OrderTexts.status(order.assignmentStatus))
                title.text = order.title
                customer.text = order.counterpart.displayName
                schedule.text = Texts.parse(order.scheduledStartAt)
                    ?.let { "${Texts.dayLabel(context, it)} · ${Texts.time(it)}" }
                    ?: context.getString(R.string.schedule_now)
                amount.text = Money.format(order.earning ?: order.totalAmount)
                root.setOnClickListener { onOpen(order) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<OrderSummary>() {
            override fun areItemsTheSame(old: OrderSummary, new: OrderSummary) = old.id == new.id
            override fun areContentsTheSame(old: OrderSummary, new: OrderSummary) = old == new
        }
    }
}
