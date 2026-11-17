package vn.servio.partner.ui.order

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.servio.core.ui.chat.ChatFragment
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.model.OrderStatus
import vn.servio.partner.databinding.FragmentOrderDetailBinding
import vn.servio.partner.databinding.ItemStatusRowBinding
import vn.servio.partner.tracking.LocationTrackingService
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.order.OrderDetailViewModel.Event
import java.time.Duration
import java.time.ZonedDateTime
import javax.inject.Inject

/** PS-14 Chi tiết đơn (đối tác). One screen for every status; the bottom buttons follow the assignment status. */
@AndroidEntryPoint
class OrderDetailFragment : Fragment(R.layout.fragment_order_detail) {

    @Inject lateinit var deviceLocation: DeviceLocation

    private val viewModel: OrderDetailViewModel by viewModels()
    private var countdownJob: Job? = null

    /** Location for #67 and the trip; notifications so the tracking notice is visible (Android 13+). */
    private val tripPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val location = DeviceLocation.PERMISSIONS.any { grants[it] == true } || deviceLocation.hasPermission
            if (location) {
                viewModel.startMoving()
            } else {
                view?.let { Snackbar.make(it, R.string.order_trip_needs_location, Snackbar.LENGTH_LONG).show() }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOrderDetailBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(showSpinner = false) }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = false
            LoadStatus.bind(binding.status, state, binding.swipeRefresh) { viewModel.load() }
            binding.actionBar.isVisible = state is UiState.Content
            if (state is UiState.Content) bind(binding, state.data)
        }
        collectWhenStarted(viewModel.busy) { busy ->
            binding.primaryButton.isEnabled = !busy
            binding.secondaryButton.isEnabled = !busy
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Message -> Snackbar.make(view, event.text, Snackbar.LENGTH_LONG).show()
                is Event.StartedMoving -> {
                    LocationTrackingService.start(requireContext(), viewModel.orderId, event.assignmentId)
                    openNavigation()
                }
                Event.Rejected -> {
                    Snackbar.make(view, R.string.order_rejected, Snackbar.LENGTH_LONG).show()
                    findNavController().navigateUp()
                }
                Event.Cancelled -> {
                    LocationTrackingService.stop(requireContext())
                    Snackbar.make(view, R.string.order_cancelled_by_me, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        super.onDestroyView()
    }

    private fun bind(binding: FragmentOrderDetailBinding, content: OrderDetailViewModel.Content) {
        val order = content.order
        val assignment = order.assignment
        val status = assignment?.status ?: order.status
        val context = requireContext()
        binding.toolbar.title = getString(R.string.order_code, order.code)

        binding.title.text = order.title
        binding.category.text = order.categoryName
        val start = Texts.parse(assignment?.reservedStartAt ?: order.scheduledStartAt)
        binding.schedule.text = start?.let { getString(R.string.order_schedule, Texts.time(it), Texts.dayLabel(context, it)) }
            ?: getString(R.string.schedule_now)
        binding.address.text = order.address
        binding.addressNote.isVisible = status == OrderStatus.PENDING
        binding.description.text = order.description
        binding.description.isVisible = order.description.isNotBlank()

        val customer = order.customer
        binding.customerName.text = customer.displayName
        binding.customerInitial.text = customer.displayName.split(' ').filter { it.isNotBlank() }
            .takeLast(2).joinToString("") { it.take(1).uppercase() }
        binding.customerPhone.isVisible = customer.phoneNumber != null
        binding.customerPhone.text = customer.phoneNumber
        binding.callButton.isVisible = customer.phoneNumber != null
        binding.callButton.setOnClickListener {
            customer.phoneNumber?.let { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) }
        }
        binding.chatButton.isVisible = order.conversationId != null
        binding.chatButton.setOnClickListener {
            order.conversationId?.let {
                findNavController().navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to it))
            }
        }

        bindPricing(binding.pricing, order)
        binding.timeline.removeAllViews()
        content.timeline.forEach { entry ->
            addRow(binding.timeline, getString(OrderTexts.status(entry.status)), Texts.dateTime(entry.createdAt))
        }
        binding.trackingNote.isVisible = status == OrderStatus.ACCEPTED || status == OrderStatus.ON_THE_WAY
        bindState(binding, order, status)
        bindActions(binding, order, status)
    }

    /** Top card: countdown while PENDING, otherwise the current status. */
    private fun bindState(binding: FragmentOrderDetailBinding, order: OrderDetail, status: OrderStatus) {
        countdownJob?.cancel()
        binding.stateTitle.setText(if (status == OrderStatus.PENDING) R.string.order_confirm_within else OrderTexts.status(status))
        binding.countdown.isVisible = status == OrderStatus.PENDING
        binding.stateNote.isVisible = true
        binding.stateNote.text = when (status) {
            OrderStatus.PENDING -> getString(R.string.order_confirm_note)
            OrderStatus.ACCEPTED -> getString(R.string.order_accepted_note)
            OrderStatus.CANCELLED -> order.cancelReason?.let { getString(R.string.order_cancel_reason, it) }.orEmpty()
            else -> ""
        }
        binding.stateNote.isVisible = binding.stateNote.text.isNotEmpty()
        if (status != OrderStatus.PENDING) return
        val deadline = Texts.parse(order.assignment?.acceptanceDeadlineAt) ?: return
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val left = Duration.between(ZonedDateTime.now(), deadline).seconds.coerceAtLeast(0)
                val text = "%02d:%02d".format(left / 60, left % 60)
                binding.countdown.text = text
                binding.primaryButton.text = getString(R.string.order_accept_with_time, text)
                if (left == 0L) {
                    viewModel.load(showSpinner = false)
                    break
                }
                delay(1_000)
            }
        }
    }

    /** Bottom buttons for each assignment status. DT-5 adds the steps after arriving. */
    private fun bindActions(binding: FragmentOrderDetailBinding, order: OrderDetail, status: OrderStatus) {
        val primary = binding.primaryButton
        val secondary = binding.secondaryButton
        primary.isVisible = true
        secondary.isVisible = true
        when (status) {
            OrderStatus.PENDING -> {
                secondary.setText(R.string.order_reject)
                secondary.setOnClickListener { askReason(R.string.order_reject_title) { viewModel.reject(it) } }
                primary.setText(R.string.order_accept)
                primary.setOnClickListener { viewModel.accept() }
            }
            OrderStatus.ACCEPTED -> {
                secondary.setText(R.string.order_partner_cancel)
                secondary.setOnClickListener { askReason(R.string.order_partner_cancel_title) { viewModel.cancel(it) } }
                primary.setText(R.string.order_start_moving)
                primary.setOnClickListener { requestTripPermissions() }
            }
            OrderStatus.ON_THE_WAY -> {
                secondary.setText(R.string.order_partner_cancel)
                secondary.setOnClickListener { askReason(R.string.order_partner_cancel_title) { viewModel.cancel(it) } }
                primary.setText(R.string.order_open_navigation)
                primary.setOnClickListener {
                    // The service may have been killed by the system: start it again (idempotent).
                    order.assignment?.let { LocationTrackingService.start(requireContext(), order.id, it.id) }
                    openNavigation()
                }
            }
            else -> {
                primary.isVisible = false
                secondary.isVisible = false
            }
        }
        binding.actionBar.isVisible = primary.isVisible || secondary.isVisible
    }

    private fun bindPricing(container: ViewGroup, order: OrderDetail) {
        container.removeAllViews()
        val pricing = order.pricing
        addRow(container, getString(R.string.order_price_quote), Money.format(pricing.subTotal))
        if (pricing.extraChargeTotal > 0) {
            addRow(container, getString(R.string.order_price_extra), Money.format(pricing.extraChargeTotal))
        }
        pricing.commissionAmount?.let { commission ->
            val rate = pricing.commissionRate?.let { "%.0f".format(it) } ?: "15"
            addRow(container, getString(R.string.order_price_commission, rate), getString(R.string.quote_minus, Money.format(commission)))
        }
        addRow(container, getString(R.string.order_price_payment), getString(R.string.order_payment_cash))
        pricing.partnerEarning?.let { addRow(container, getString(R.string.order_price_earning), Money.format(it)) }
    }

    private fun addRow(container: ViewGroup, label: String, value: String) {
        val row = ItemStatusRowBinding.inflate(layoutInflater, container, false)
        row.label.text = label
        row.value.text = value
        container.addView(row.root)
    }

    private fun requestTripPermissions() {
        val wanted = buildList {
            addAll(DeviceLocation.PERMISSIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        tripPermissions.launch(wanted.toTypedArray())
    }

    /** Asks for a 3–500 character reason (#66, #152), then calls [onReason]. */
    private fun askReason(titleRes: Int, onReason: (String) -> Unit) {
        val context = requireContext()
        val layout = TextInputLayout(context).apply {
            setPadding(resources.getDimensionPixelSize(vn.servio.core.R.dimen.screen_padding), 0,
                resources.getDimensionPixelSize(vn.servio.core.R.dimen.screen_padding), 0)
            hint = getString(R.string.order_reason_hint)
        }
        val input = TextInputEditText(layout.context)
        layout.addView(input)
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(layout)
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty()
                if (reason.length >= MIN_REASON) {
                    onReason(reason)
                } else {
                    view?.let { Snackbar.make(it, R.string.order_reason_invalid, Snackbar.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private fun openNavigation() {
        findNavController().navigate(R.id.ps15, bundleOf(ARG_ORDER_ID to viewModel.orderId))
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"
        private const val MIN_REASON = 3
    }
}
