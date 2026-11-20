package vn.servio.customer.ui.orderdetail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import vn.servio.core.ui.chat.ChatFragment
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderActor
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.repository.OrderUpdates
import vn.servio.customer.databinding.FragmentOrderDetailBinding
import vn.servio.customer.databinding.ItemOrderStepBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.completion.ConfirmCompletionViewModel
import vn.servio.customer.ui.dispute.CreateDisputeViewModel
import vn.servio.customer.ui.dispute.DisputeRules
import vn.servio.customer.ui.order.OrderFormat
import vn.servio.customer.ui.order.OrderRoutes
import vn.servio.customer.ui.review.SubmitReviewViewModel
import vn.servio.customer.ui.order.OrderStatusChip
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.order.TrackingUi
import vn.servio.customer.ui.tracking.OrderTrackingViewModel
import vn.servio.customer.ui.orderdetail.OrderDetailUi.Line
import vn.servio.customer.ui.orderdetail.OrderDetailUi.Note
import vn.servio.customer.ui.orderdetail.OrderDetailUi.StatusArea
import vn.servio.customer.ui.orderdetail.OrderDetailUi.StepKind
import vn.servio.customer.ui.orderdetail.OrderDetailUi.StepState
import vn.servio.customer.ui.requestdetail.QuoteFormat
import vn.servio.customer.ui.requestdetail.QuoteTexts
import javax.inject.Inject

/**
 * CS-20 Chi tiết đơn hàng (every status; ETA while ON_THE_WAY, CS-21 behind "Theo dõi đường đi", CS-20b through [ArrivalPrompt],
 * CS-22 [ExtraChargeSheet] for pending extra charges). What is shown per status is decided in
 * [OrderDetailUi]; this class only formats. "Huỷ đơn" opens [CancelOrderDialogFragment] (CS-20c), which answers
 * through a fragment result.
 */
@AndroidEntryPoint
class OrderDetailFragment : Fragment(R.layout.fragment_order_detail) {

    @Inject lateinit var httpClient: OkHttpClient

    @Inject lateinit var orderUpdates: OrderUpdates

    private val viewModel: OrderDetailViewModel by viewModels()

    private var countdownJob: Job? = null
    private var etaJob: Job? = null
    private var arrivalPrompt: ArrivalPrompt? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOrderDetailBinding.bind(view)
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_dispute) openDispute()
            true
        }
        binding.notFoundBackButton.setOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.serviceRow.label.setText(R.string.order_detail_service)
        binding.scheduleRow.label.setText(R.string.order_detail_schedule)
        binding.addressRow.label.setText(R.string.order_detail_address)
        binding.priceRow.label.setText(R.string.order_detail_price)
        binding.paymentRow.label.setText(R.string.order_detail_payment)
        binding.extraRow.label.setText(R.string.order_detail_extra_approved)
        binding.totalRow.label.setText(R.string.order_detail_total)
        binding.totalRow.label.setTextColor(requireContext().getColor(vn.servio.core.R.color.servio_text_primary))
        binding.totalRow.label.setTypeface(null, Typeface.BOLD)
        OrderRoutes.listen(this, R.id.cs20) { view }

        arrivalPrompt = ArrivalPrompt(
            host = this,
            orderUpdates = orderUpdates,
            orderId = { viewModel.orderId },
            names = { viewModel.state.value.detail?.let { it.primary?.partner?.displayName to it.address } },
            reload = { viewModel.load() },
        ).also { it.attach() }

        childFragmentManager.setFragmentResultListener(CancelOrderDialogFragment.RESULT_KEY, viewLifecycleOwner) { _, result ->
            val message = when {
                result.getBoolean(CancelOrderDialogFragment.RESULT_NOT_CANCELLABLE) -> getString(R.string.order_cancel_not_cancellable)
                else -> {
                    val fee = result.getLong(CancelOrderDialogFragment.RESULT_FEE)
                    if (fee > 0) getString(R.string.order_cancel_done_fee, OrderFormat.money(requireContext(), fee)) else getString(R.string.order_cancel_done)
                }
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            viewModel.load()
        }

        collectWhenStarted(viewModel.state) { state ->
            val detail = state.detail
            binding.progress.isVisible = state.loading && detail == null && !state.notFound
            binding.swipeRefresh.isRefreshing = state.loading && detail != null
            binding.notFoundGroup.isVisible = state.notFound
            binding.errorGroup.isVisible = !state.notFound && detail == null && state.error != null
            binding.errorText.text = state.error
            binding.content.isVisible = !state.notFound && detail != null
            binding.swipeRefresh.isEnabled = !state.notFound
            binding.toolbar.title = detail?.let { getString(R.string.order_detail_title, it.code) } ?: getString(R.string.order_detail_title_loading)
            binding.toolbar.menu.findItem(R.id.action_dispute).isVisible = !state.notFound && state.canDispute
            if (detail != null && !state.notFound) {
                bind(binding, images, state)
                arrivalPrompt?.showIfPending()
            } else {
                stopCountdown()
                stopEta()
            }
        }
        collectWhenStarted(viewModel.events) { event ->
            val text = when (event) {
                is OrderDetailViewModel.Event.Message -> event.text
                is OrderDetailViewModel.Event.ChargeAnswered ->
                    if (event.approved) getString(R.string.extra_charge_approved_done, OrderFormat.money(requireContext(), event.amount))
                    else getString(R.string.extra_charge_rejected_done)
                is OrderDetailViewModel.Event.ChargeFailed -> when (event.failure) {
                    OrderDetailUi.ChargeFailure.HANDLED -> getString(R.string.extra_charge_handled)
                    OrderDetailUi.ChargeFailure.DISPUTED -> getString(R.string.extra_charge_disputed)
                    // 404: the reload shows where the order stands; OTHER never comes here (the sheet keeps it).
                    OrderDetailUi.ChargeFailure.NOT_FOUND, OrderDetailUi.ChargeFailure.OTHER -> null
                }
            }
            text?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
        }
        // CS-22 opens by itself while CS-20 is resumed: on resume and after each #63 answer (ExtraChargeRequested
        // reloads it), with the oldest pending charge the customer has not closed in this visit.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.state.collect { showCharge(fromWarning = false) }
            }
        }

        if (savedInstanceState == null || viewModel.state.value.detail == null) viewModel.load()
    }

    private fun bind(binding: FragmentOrderDetailBinding, images: RemoteImages, state: OrderDetailViewModel.State) {
        val order = state.detail ?: return
        val context = requireContext()
        val weekdays = QuoteFormat.weekdays(context)
        bindStatus(binding, state)

        // Partner
        val partner = order.primary?.partner
        val name = partner?.displayName.orEmpty()
        binding.partnerName.text = name.ifBlank { getString(R.string.order_missing_value) }
        binding.avatarInitials.text = QuoteTexts.initials(name)
        val avatarUrl = partner?.avatarUrl
        if (binding.avatarImage.getTag(R.id.avatarImage) != avatarUrl) {
            binding.avatarImage.setTag(R.id.avatarImage, avatarUrl)
            images.load(avatarUrl, binding.avatarImage)
        }
        val rating = OrderTexts.rating(partner?.rating, partner?.totalReviews)
        with(binding.rating) {
            star.isVisible = rating.score != null
            score.isVisible = rating.score != null
            score.text = rating.score
            count.isVisible = rating.score == null || rating.reviewCount != null
            count.text = when {
                rating.score == null -> getString(R.string.quote_no_reviews)
                rating.reviewCount != null -> getString(R.string.quote_review_count, rating.reviewCount)
                else -> null
            }
        }

        val actions = OrderDetailUi.actions(order)
        binding.phoneNote.isVisible = actions.phoneNote
        binding.chatButton.isVisible = actions.chat
        binding.chatButton.setOnClickListener { order.conversationId?.let(::openChat) }
        binding.callButton.isVisible = actions.call
        binding.callButton.setOnClickListener { partner?.phoneNumber?.let(::dial) }
        binding.confirmButton.isVisible = actions.confirm
        binding.confirmButton.setOnClickListener { openConfirm() }
        binding.reviewButton.isVisible = state.reviewable
        binding.reviewButton.setOnClickListener { order.primary?.id?.let(::openReview) }
        binding.cancelButton.isVisible = actions.cancel
        binding.cancelButton.setText(if (OrderDetailUi.cancelIsFree(state.cancelFee)) R.string.order_cancel_free else R.string.order_cancel)
        binding.cancelButton.setOnClickListener { openCancel() }

        // Thông tin công việc
        val missing = getString(R.string.order_missing_value)
        binding.serviceRow.value.text = order.categoryName.ifBlank { missing }
        binding.scheduleRow.value.text = OrderTexts.moment(order.scheduledStartAt, weekdays) ?: missing
        binding.addressRow.value.text = order.address.ifBlank { missing }
        binding.priceRow.value.text = order.primary?.let { OrderFormat.money(context, it.amount) } ?: missing
        val showsTotal = OrderDetailUi.showsTotal(order)
        binding.extraRow.root.isVisible = showsTotal
        binding.totalRow.root.isVisible = showsTotal
        binding.extraRow.value.text = getString(R.string.extra_charge_plus, OrderFormat.money(context, order.pricing.extraChargeTotal))
        binding.totalRow.value.text = OrderFormat.money(context, order.pricing.totalAmount)
        binding.paymentRow.value.text = paymentLabel(order.paymentMethod)?.let(::getString) ?: missing

        bindTimeline(binding, order)
    }

    private fun bindStatus(binding: FragmentOrderDetailBinding, state: OrderDetailViewModel.State) {
        val order = state.detail ?: return
        val context = requireContext()
        val weekdays = QuoteFormat.weekdays(context)
        val result = state.disputes
            ?.takeIf { order.status != OrderStatus.DISPUTED }
            ?.let(DisputeRules::closedCase)
            ?.let(DisputeRules::resultLine)
        binding.disputeResult.isVisible = result != null
        binding.disputeResult.text = result?.let {
            getString(R.string.order_detail_dispute_result, it.code, it.note ?: getString(R.string.order_detail_dispute_result_empty))
        }
        when (val area = OrderDetailUi.statusArea(order)) {
            is StatusArea.Pending -> {
                binding.banner.isVisible = true
                binding.statusCard.isVisible = false
                binding.banner.backgroundTintList = null
                binding.bannerIcon.setImageResource(R.drawable.ic_hourglass)
                binding.bannerIcon.imageTintList = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_text_accent))
                binding.bannerTitle.setText(R.string.order_detail_pending_title)
                binding.bannerTitle.setTextColor(context.getColor(vn.servio.core.R.color.servio_text_primary))
                startCountdown(binding, area)
            }
            StatusArea.Disputed -> {
                stopCountdown()
                stopEta()
                binding.banner.isVisible = true
                binding.statusCard.isVisible = false
                binding.banner.backgroundTintList = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_error_container))
                binding.bannerIcon.setImageResource(R.drawable.ic_flag)
                binding.bannerIcon.imageTintList = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_error))
                binding.bannerTitle.setText(R.string.order_detail_disputed)
                binding.bannerTitle.setTextColor(context.getColor(vn.servio.core.R.color.servio_error))
                val line = state.disputes?.let(DisputeRules::openCase)?.let { DisputeRules.bannerLine(it, weekdays) }
                binding.bannerText.isVisible = line != null
                binding.bannerText.text = line?.let {
                    if (it.due != null) getString(R.string.order_detail_dispute_line, it.code, it.due)
                    else getString(R.string.order_detail_dispute_code, it.code)
                }
            }
            is StatusArea.AwaitingPayment -> {
                stopCountdown()
                stopEta()
                binding.banner.isVisible = true
                binding.statusCard.isVisible = false
                binding.banner.backgroundTintList = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_error_container))
                binding.bannerIcon.setImageResource(R.drawable.ic_warning)
                binding.bannerIcon.imageTintList = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_error))
                binding.bannerTitle.setText(R.string.order_status_unpaid)
                binding.bannerTitle.setTextColor(context.getColor(vn.servio.core.R.color.servio_error))
                binding.bannerText.isVisible = true
                binding.bannerText.text = getString(R.string.order_detail_unpaid_text, OrderFormat.money(context, area.amount))
            }
            is StatusArea.Card -> {
                stopCountdown()
                binding.banner.isVisible = false
                binding.statusCard.isVisible = true
                OrderStatusChip.bind(binding.statusChip, area.status)
                val pendingCharges = OrderDetailUi.pendingCharges(order).size
                binding.chargeWarning.isVisible = pendingCharges > 0
                binding.chargeWarning.text = getString(R.string.order_detail_pending_charges, pendingCharges)
                binding.chargeWarning.setOnClickListener { showCharge(fromWarning = true) }
                val tracked = area.status == OrderStatus.ON_THE_WAY
                binding.etaLine.isVisible = false
                binding.trackButton.isVisible = tracked && order.primary != null
                binding.trackButton.setOnClickListener { order.primary?.id?.let(::openTracking) }
                if (tracked) {
                    startEta(binding)
                } else {
                    stopEta()
                    val text = statusLine(area, weekdays)
                    binding.statusLine.text = text
                    binding.statusLine.isVisible = text != null
                }
            }
        }
    }

    /** One line under the chip; a line without its time is left out (the done-by-partner line keeps its first half). */
    private fun statusLine(area: StatusArea.Card, weekdays: List<String>): String? {
        val time = OrderTexts.time(area.at)
        val moment = OrderTexts.moment(area.at, weekdays)
        return when (area.line) {
            Line.SCHEDULED -> moment?.let { getString(R.string.order_detail_line_scheduled, it) }
            Line.ON_THE_WAY -> time?.let { getString(R.string.order_detail_line_on_the_way, it) }
            Line.ARRIVED -> time?.let { getString(R.string.order_detail_line_arrived, it) }
            Line.STARTED -> time?.let { getString(R.string.order_detail_line_started, it) }
            Line.AUTO_CONFIRM -> moment?.let { getString(R.string.order_detail_line_auto_confirm, it) }
                ?: getString(R.string.order_detail_line_done_by_partner)
            Line.COMPLETED -> moment?.let { getString(R.string.order_detail_line_completed, it) }
            Line.CANCELLED -> moment?.let { getString(R.string.order_detail_line_cancelled, it) }
            Line.NONE -> null
        }
    }

    /**
     * ON_THE_WAY card: "Khoảng N phút nữa" + "Cập nhật lúc HH:mm · cách X km" from the last #76 / PartnerLocationUpdated.
     * Re-checked every 5 s while started, so a fix older than 30 s turns into "Chưa cập nhật · vị trí lúc HH:mm".
     */
    private fun startEta(binding: FragmentOrderDetailBinding) {
        bindEta(binding)
        if (etaJob?.isActive == true) return
        etaJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    bindEta(binding)
                    delay(TrackingUi.CHECK_EVERY_MILLIS)
                }
            }
        }
    }

    private fun bindEta(binding: FragmentOrderDetailBinding) {
        val (title, line) = when (val view = TrackingUi.of(viewModel.state.value.tracking)) {
            TrackingUi.View.Unknown -> getString(R.string.order_eta_unknown) to null
            TrackingUi.View.NoLocation -> getString(R.string.order_eta_no_location) to null
            is TrackingUi.View.Stale -> (OrderTexts.time(view.capturedAt)?.let { getString(R.string.order_eta_stale, it) } ?: getString(R.string.order_eta_unknown)) to null
            is TrackingUi.View.Live -> getString(R.string.order_eta_minutes, view.etaMinutes) to OrderTexts.time(view.capturedAt)?.let {
                getString(R.string.order_eta_updated, it, getString(R.string.order_distance_km, TrackingUi.distance(view.distanceKm)))
            }
        }
        binding.statusLine.isVisible = true
        binding.statusLine.text = title
        binding.etaLine.isVisible = line != null
        binding.etaLine.text = line
    }

    private fun stopEta() {
        etaJob?.cancel()
        etaJob = null
    }

    /** "Đối tác có N phút để xác nhận · còn mm:ss", ticking each second while the screen is started. */
    private fun startCountdown(binding: FragmentOrderDetailBinding, area: StatusArea.Pending) {
        countdownJob?.cancel()
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val left = OrderTexts.secondsUntil(area.deadlineAt)
                    binding.bannerText.isVisible = left != null
                    if (left == null) break
                    if (left <= 0) {
                        binding.bannerText.setText(R.string.order_detail_pending_expired)
                        viewModel.onDeadlinePassed()
                        break
                    }
                    val clock = OrderTexts.countdown(left)
                    binding.bannerText.text = area.windowMinutes
                        ?.let { getString(R.string.order_detail_pending_window, it, clock) }
                        ?: getString(R.string.order_detail_pending_left, clock)
                    delay(1_000L - System.currentTimeMillis() % 1_000L)
                }
            }
        }
    }

    private fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    private fun bindTimeline(binding: FragmentOrderDetailBinding, order: OrderDetail) {
        val context = requireContext()
        val weekdays = QuoteFormat.weekdays(context)
        val timeline = OrderDetailUi.timeline(order)
        val inflater = LayoutInflater.from(context)
        binding.timeline.removeAllViews()
        val total = timeline.steps.size + if (timeline.cancelled != null) 1 else 0
        timeline.steps.forEachIndexed { index, step ->
            val row = ItemOrderStepBinding.inflate(inflater, binding.timeline, true)
            row.title.setText(stepTitle(step))
            row.title.setTypeface(null, if (step.state == StepState.CURRENT) Typeface.BOLD else Typeface.NORMAL)
            row.title.setTextColor(context.getColor(if (step.state == StepState.TODO) vn.servio.core.R.color.servio_text_secondary else vn.servio.core.R.color.servio_text_primary))
            val note = stepNote(step.note, weekdays)
            row.note.text = note
            row.note.isVisible = note != null
            when (step.state) {
                StepState.DONE -> dot(row, vn.servio.core.R.color.servio_success, R.drawable.ic_check)
                StepState.CURRENT -> dot(row, vn.servio.core.R.color.servio_primary, null)
                StepState.TODO -> dot(row, vn.servio.core.R.color.servio_surface_variant, null)
            }
            val last = index == total - 1
            row.line.isVisible = !last
            val nextDone = timeline.steps.getOrNull(index + 1)?.state?.let { it != StepState.TODO } ?: (timeline.cancelled != null)
            row.line.setBackgroundColor(context.getColor(if (step.state == StepState.DONE && nextDone) vn.servio.core.R.color.servio_success else vn.servio.core.R.color.servio_divider))
        }
        timeline.cancelled?.let { cancelled ->
            val row = ItemOrderStepBinding.inflate(inflater, binding.timeline, true)
            dot(row, vn.servio.core.R.color.servio_error, R.drawable.ic_close)
            row.line.isVisible = false
            val separator = getString(R.string.order_step_separator)
            val parts = listOfNotNull(
                OrderTexts.time(cancelled.at)?.let { getString(R.string.order_step_cancelled_at, it) } ?: getString(R.string.order_step_cancelled),
                actorLabel(cancelled.by)?.let { getString(R.string.order_step_cancelled_by, getString(it)) },
                cancelled.reason,
            )
            row.title.text = parts.joinToString(separator)
            row.title.setTypeface(null, Typeface.BOLD)
            row.title.setTextColor(context.getColor(vn.servio.core.R.color.servio_error))
            row.note.isVisible = false
        }
    }

    private fun dot(row: ItemOrderStepBinding, @ColorRes fill: Int, icon: Int?) {
        val context = requireContext()
        row.dot.backgroundTintList = ColorStateList.valueOf(context.getColor(fill))
        if (icon == null) {
            row.dot.setImageDrawable(null)
        } else {
            row.dot.setImageResource(icon)
            row.dot.imageTintList = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_surface))
        }
    }

    @StringRes
    private fun stepTitle(step: OrderDetailUi.Step): Int = when (step.kind) {
        StepKind.CREATED -> R.string.order_step_created
        StepKind.PARTNER -> if (step.waiting) R.string.order_step_waiting_partner else R.string.order_step_partner_confirmed
        StepKind.ON_THE_WAY -> R.string.order_step_on_the_way
        StepKind.ARRIVED -> R.string.order_step_arrived
        StepKind.IN_PROGRESS -> R.string.order_step_in_progress
        StepKind.COMPLETED -> R.string.order_step_completed
    }

    private fun stepNote(note: Note, weekdays: List<String>): String? = when (note) {
        is Note.At -> listOfNotNull(
            OrderTexts.moment(note.at, weekdays),
            note.pendingCharges.takeIf { it > 0 }?.let { getString(R.string.order_step_pending_charges, it) },
        ).takeIf { it.isNotEmpty() }?.joinToString(getString(R.string.order_step_separator))
        is Note.ExpiresAt -> OrderTexts.time(note.at)?.let { getString(R.string.order_step_expires_at, it) }
        is Note.AwaitingConfirm -> OrderTexts.moment(note.autoConfirmAt, weekdays)
            ?.let { getString(R.string.order_step_awaiting_confirm, it) }
            ?: getString(R.string.order_step_awaiting_confirm_no_time)
        Note.Unpaid -> getString(R.string.order_status_unpaid)
        Note.None -> null
    }

    @StringRes
    private fun actorLabel(actor: OrderActor?): Int? = when (actor) {
        OrderActor.CUSTOMER -> R.string.order_actor_customer
        OrderActor.PARTNER -> R.string.order_actor_partner
        OrderActor.SYSTEM -> R.string.order_actor_system
        OrderActor.ADMIN -> R.string.order_actor_admin
        null -> null
    }

    @StringRes
    private fun paymentLabel(method: String?): Int? = when (method) {
        PAYMENT_CASH -> R.string.order_payment_cash
        else -> null
    }

    private fun openCancel() {
        if (childFragmentManager.findFragmentByTag(CancelOrderDialogFragment.TAG) != null) return
        val code = viewModel.state.value.detail?.code.orEmpty()
        CancelOrderDialogFragment.newInstance(viewModel.orderId, code).show(childFragmentManager, CancelOrderDialogFragment.TAG)
    }

    /** CS-28 (core ChatFragment); Back returns here. */
    private fun openChat(conversationId: String) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs20) return
        navController.navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to conversationId))
    }

    /** CS-21 Theo dõi bản đồ; Back returns here. */
    private fun openTracking(assignmentId: String) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs20) return
        navController.navigate(R.id.cs21, bundleOf(OrderTrackingViewModel.ARG_ORDER_ID to viewModel.orderId, OrderTrackingViewModel.ARG_ASSIGNMENT_ID to assignmentId))
    }

    /**
     * CS-22 on the oldest pending charge: by itself only while resumed and skipping the charges closed in this visit;
     * from the warning line also a closed one. Never two sheets, never over a saved state.
     */
    private fun showCharge(fromWarning: Boolean) {
        if (!isResumed) return
        val manager = childFragmentManager
        if (manager.isStateSaved || manager.findFragmentByTag(ExtraChargeSheet.TAG) != null) return
        val charge = viewModel.state.value.chargeToOpen(includeClosed = fromWarning) ?: return
        ExtraChargeSheet.newInstance(charge.id).show(manager, ExtraChargeSheet.TAG)
    }

    /** The sheet went away (answered, closed, no longer pending): open the next pending charge if there is one. */
    fun onChargeSheetGone() {
        view?.post { showCharge(fromWarning = false) }
    }

    /** CS-23 Xác nhận hoàn thành; after #71 it becomes CS-26, whose Back returns here. */
    private fun openConfirm() {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs20) return
        navController.navigate(R.id.cs23, bundleOf(ConfirmCompletionViewModel.ARG_ORDER_ID to viewModel.orderId))
    }

    /** CS-33 Gửi khiếu nại; a sent case comes back as "Đã gửi khiếu nại" (OrderRoutes) and the order reloads as DISPUTED. */
    private fun openDispute() {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs20) return
        navController.navigate(R.id.cs33, bundleOf(CreateDisputeViewModel.ARG_ORDER_ID to viewModel.orderId))
    }

    /** CS-26 Đánh giá đối tác; a sent review comes back as "Đã gửi đánh giá" (OrderRoutes) and the button goes. */
    private fun openReview(assignmentId: String) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs20) return
        navController.navigate(R.id.cs26, SubmitReviewViewModel.args(viewModel.orderId, assignmentId))
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumed()
    }

    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (_: ActivityNotFoundException) {
            // No phone app (some tablets): the number is not shown elsewhere, nothing else to do.
        }
    }

    override fun onDestroyView() {
        stopCountdown()
        stopEta()
        arrivalPrompt = null
        super.onDestroyView()
    }

    private companion object {
        const val PAYMENT_CASH = "CASH"
    }
}
