package vn.servio.partner.ui.work

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.model.ExtraChargeStatus
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.model.OrderStatus
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.databinding.FragmentCashBinding
import vn.servio.partner.databinding.ItemStatusRowBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.order.OrderDetailFragment
import javax.inject.Inject

@HiltViewModel
class CashViewModel @Inject constructor(
    private val repository: OrderRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val orderId: String = checkNotNull(savedStateHandle[OrderDetailFragment.ARG_ORDER_ID])

    private val _state = MutableStateFlow<UiState<OrderDetail>>(UiState.Loading)
    val state: StateFlow<UiState<OrderDetail>> = _state.asStateFlow()

    init {
        load()
        // The customer confirms on CS-23 (#71) or the server auto-confirms: OrderStatusChanged arrives here.
        viewModelScope.launch {
            repository.changes().filter { it == null || it == orderId }.collect { load(showSpinner = false) }
        }
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.get(orderId)
            if (result is ApiResult.Success || showSpinner) _state.value = result.toUiState()
        }
    }
}

/**
 * PS-19 Thu tiền mặt: the amount to collect. There is no "Đã thu" button on purpose (spec): the order completes when
 * the customer confirms (#71) or after order.auto_confirm_hours; the commission debt is then recorded on PS-21.
 */
@AndroidEntryPoint
class CashFragment : Fragment(R.layout.fragment_cash) {

    private val viewModel: CashViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCashBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(showSpinner = false) }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = false
            LoadStatus.bind(binding.status, state, binding.swipeRefresh) { viewModel.load() }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content) bind(binding, state.data)
        }
    }

    private fun bind(binding: FragmentCashBinding, order: OrderDetail) {
        val pricing = order.pricing
        val done = order.status == OrderStatus.COMPLETED
        binding.amount.text = Money.format(pricing.totalAmount)
        binding.stateIcon.setImageResource(if (done) R.drawable.ic_check else R.drawable.ic_hourglass)
        binding.stateTitle.setText(if (done) R.string.cash_done_title else R.string.cash_waiting_title)
        binding.stateText.text = when {
            done -> getString(R.string.cash_done_text)
            order.status == OrderStatus.DISPUTED -> getString(R.string.cash_disputed_text)
            else -> Texts.parse(order.autoConfirmAt)
                ?.let { getString(R.string.cash_waiting_text_auto, "${Texts.time(it)} ${Texts.dayLabel(requireContext(), it)}") }
                ?: getString(R.string.cash_waiting_text)
        }

        binding.breakdown.removeAllViews()
        addRow(binding.breakdown, getString(R.string.order_price_quote), Money.format(pricing.subTotal))
        order.extraCharges.filter { it.status == ExtraChargeStatus.APPROVED }.forEach {
            addRow(binding.breakdown, getString(R.string.complete_extra_line, it.description), Money.format(it.amount))
        }
        pricing.commissionAmount?.let {
            addRow(binding.breakdown, getString(R.string.cash_commission_debt), getString(R.string.quote_minus, Money.format(it)))
        }
        pricing.partnerEarning?.let { addRow(binding.breakdown, getString(R.string.order_price_earning), Money.format(it)) }

        val primary = binding.actionBar.primaryButton
        if (done) {
            primary.setText(R.string.cash_rate_customer)
            primary.setOnClickListener {
                findNavController().navigate(R.id.ps27, bundleOf(OrderDetailFragment.ARG_ORDER_ID to order.id))
            }
        } else {
            primary.setText(R.string.cash_back_to_orders)
            primary.setOnClickListener {
                if (!findNavController().popBackStack(R.id.ps13, false)) findNavController().navigateUp()
            }
        }
    }

    private fun addRow(container: ViewGroup, label: String, value: String) {
        val row = ItemStatusRowBinding.inflate(layoutInflater, container, false)
        row.label.text = label
        row.value.text = value
        container.addView(row.root)
    }
}
