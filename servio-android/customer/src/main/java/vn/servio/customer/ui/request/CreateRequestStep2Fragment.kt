package vn.servio.customer.ui.request

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import vn.servio.core.R as CoreR
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.databinding.FragmentCreateRequestStep2Binding
import vn.servio.customer.ui.address.AddressBookFragment
import vn.servio.customer.ui.request.CreateRequestViewModel.FieldError
import vn.servio.customer.ui.request.CreateRequestViewModel.ScheduleUi
import java.time.LocalDate

/**
 * CS-09 Tạo yêu cầu – Bước 2 Địa chỉ & thời gian: the work address (default one, "Đổi" through CS-13 in select mode,
 * or a new one pinned on CS-12) and "Càng sớm càng tốt" or a 2-hour slot up to 30 days ahead.
 * Back returns to CS-08 without asking: nothing typed here is lost, the draft lives in [CreateRequestViewModel].
 */
@AndroidEntryPoint
class CreateRequestStep2Fragment : Fragment(R.layout.fragment_create_request_step2) {

    private val viewModel: CreateRequestViewModel by navGraphViewModels(R.id.create_request_graph) {
        defaultViewModelProviderFactory
    }

    private var binding: FragmentCreateRequestStep2Binding? = null

    /**
     * Brings the selected day chip into view once the day row is shown: on every new view (rotation, back from
     * CS-08 / CS-12 / CS-13, restore after process death), on "Hẹn lịch", and when the screen moves the day itself.
     */
    private var pendingDayScroll = false

    /** Day shown selected by the last bind, to tell a new selection from a re-bind. */
    private var lastSelectedDate: LocalDate? = null

    /** Day the customer just tapped: already in view, so it does not scroll. Used once. */
    private var tappedDate: LocalDate? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCreateRequestStep2Binding.bind(view)
        this.binding = binding
        pendingDayScroll = true
        lastSelectedDate = null
        tappedDate = null
        CreateRequestSteps.bind(binding.steps, step = 2, label = R.string.create_request_step_address_time)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.changeAddressButton.setOnClickListener { changeAddress() }
        binding.pickOnMapRow.setOnClickListener { navigateFromHere(R.id.cs12) }
        observeAddressResults()
        viewModel.loadAddressIfNeeded()

        val dayAdapter = ScheduleChipAdapter(fillWidth = false) { item ->
            val date = LocalDate.parse(item.key)
            if (!item.selected) tappedDate = date
            viewModel.setScheduledDate(date)
        }
        val slotAdapter = ScheduleChipAdapter(fillWidth = true) { viewModel.setScheduledHour(it.key.toInt()) }
        binding.dayList.adapter = dayAdapter
        binding.slotList.adapter = slotAdapter
        // The change animation fades items back to alpha 1, which would undo the faded look of a slot that expired.
        binding.dayList.itemAnimator = null
        binding.slotList.itemAnimator = null
        binding.nowCard.setOnClickListener { viewModel.setScheduleNow() }
        binding.scheduledCard.setOnClickListener { viewModel.chooseScheduled() }

        binding.continueButton.setOnClickListener { onContinue() }

        collectWhenStarted(viewModel.address) { state -> binding.bindAddress(state) }
        collectWhenStarted(viewModel.draft.map { it.scheduleType }.distinctUntilChanged()) { type ->
            binding.bindScheduleType(type)
        }
        collectWhenStarted(viewModel.schedule) { schedule -> binding.bindSchedule(schedule, dayAdapter, slotAdapter) }
        collectWhenStarted(combine(viewModel.fieldErrors, viewModel.address, ::Pair)) { (errors, address) ->
            binding.bindErrors(errors, address)
        }
    }

    /** Slots may have passed while the app was in the background (or killed). */
    override fun onResume() {
        super.onResume()
        if (viewModel.refreshSchedule()) showMessage(R.string.create_request_slot_expired)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // region Address

    private fun FragmentCreateRequestStep2Binding.bindAddress(state: UiState<Address?>) {
        when (state) {
            UiState.Loading -> {
                addressTitle.setText(R.string.create_request_address_loading)
                addressLine.isVisible = false
                changeAddressButton.isVisible = false
            }
            is UiState.Error -> {
                addressTitle.text = state.message
                addressLine.isVisible = false
                changeAddressButton.setText(CoreR.string.action_retry)
                changeAddressButton.isVisible = true
            }
            is UiState.Content -> {
                val address = state.data
                addressTitle.text = when {
                    address == null -> getString(R.string.create_request_address_empty)
                    address.isDefault -> getString(R.string.create_request_address_default, address.label)
                    else -> address.label
                }
                addressLine.text = address?.fullAddress
                addressLine.isVisible = address != null
                // An empty book has nothing to pick from: "Chọn vị trí trên bản đồ" adds the first address.
                changeAddressButton.setText(R.string.create_request_change_address)
                changeAddressButton.isVisible = address != null
            }
        }
    }

    /** "Đổi": CS-13 in select mode, ticked on the address in use. "Thử lại" runs the failed load again. */
    private fun changeAddress() {
        when (val state = viewModel.address.value) {
            UiState.Loading -> Unit
            is UiState.Error -> viewModel.retryAddress()
            is UiState.Content -> navigateFromHere(
                R.id.cs13,
                bundleOf(
                    AddressBookFragment.ARG_SELECT_MODE to true,
                    AddressBookFragment.ARG_SELECTED_ADDRESS_ID to state.data?.id,
                ),
            )
        }
    }

    /**
     * The address picked on CS-13 or saved on CS-12. Always reloaded, even when it is the same id (it may have been
     * edited); the key is removed once applied so a recreated view does not apply it again.
     */
    private fun observeAddressResults() {
        val handle = findNavController().getBackStackEntry(R.id.cs09).savedStateHandle
        listOf(AddressBookFragment.RESULT_SELECTED_ADDRESS_ID, AddressBookFragment.RESULT_SAVED_ADDRESS_ID).forEach { key ->
            handle.getLiveData<String>(key).observe(viewLifecycleOwner) { id ->
                if (id == null) return@observe
                handle.remove<String>(key)
                viewModel.selectAddress(id)
            }
        }
    }

    // endregion

    // region Time

    private fun FragmentCreateRequestStep2Binding.bindScheduleType(type: ScheduleType) {
        val scheduled = type == ScheduleType.SCHEDULED
        nowCard.isChecked = !scheduled
        nowCheck.isVisible = !scheduled
        scheduledCard.isChecked = scheduled
        scheduledCheck.isVisible = scheduled
        scheduleGroup.isVisible = scheduled
        if (scheduled) {
            pendingDayScroll = true
            scrollDayIntoViewIfPending()
        }
    }

    private fun FragmentCreateRequestStep2Binding.bindSchedule(
        schedule: ScheduleUi,
        dayAdapter: ScheduleChipAdapter,
        slotAdapter: ScheduleChipAdapter,
    ) {
        val context = requireContext()
        val days = schedule.days.map {
            ScheduleChipAdapter.Item(it.date.toString(), ScheduleLabels.day(context, it.date), it.enabled, it.selected)
        }
        val selected = schedule.days.getOrNull(schedule.selectedDayIndex)?.date
        if (selected != lastSelectedDate) {
            // A day the screen picked (first day with a slot, today ran out of slots) may be off screen.
            if (selected != tappedDate) pendingDayScroll = true
            tappedDate = null
            lastSelectedDate = selected
        }
        dayAdapter.submitList(days) { scrollDayIntoViewIfPending() }
        slotAdapter.submitList(
            schedule.slots.map {
                ScheduleChipAdapter.Item(it.startHour.toString(), ScheduleLabels.slot(context, it.startHour), it.enabled, it.selected)
            },
        )
    }

    /**
     * Jumps (no animation) to the selected chip when it is not fully visible, after the list is laid out. Waits while
     * the row is hidden or the list is not submitted yet.
     */
    private fun scrollDayIntoViewIfPending() {
        val dayList = binding?.dayList ?: return
        if (!pendingDayScroll) return
        dayList.post {
            val binding = binding ?: return@post
            if (!pendingDayScroll || !binding.scheduleGroup.isVisible) return@post
            val adapter = binding.dayList.adapter as? ScheduleChipAdapter ?: return@post
            val index = adapter.currentList.indexOfFirst { it.selected }
            if (index < 0) return@post
            pendingDayScroll = false
            val layoutManager = binding.dayList.layoutManager as LinearLayoutManager
            val first = layoutManager.findFirstCompletelyVisibleItemPosition()
            val last = layoutManager.findLastCompletelyVisibleItemPosition()
            if (first < 0 || index !in first..last) binding.dayList.scrollToPosition(index)
        }
    }

    // endregion

    /** "Chọn địa chỉ…" when there is none; "Chưa tải được…" when the card failed to load (none while it loads). */
    private fun FragmentCreateRequestStep2Binding.bindErrors(errors: Set<FieldError>, address: UiState<Address?>) {
        val addressMessage = when {
            FieldError.ADDRESS_REQUIRED in errors -> R.string.create_request_address_required
            FieldError.ADDRESS_NOT_READY in errors && address is UiState.Error -> R.string.create_request_address_not_ready
            else -> null
        }
        addressError.isVisible = addressMessage != null
        if (addressMessage != null) addressError.setText(addressMessage)
        slotError.isVisible = FieldError.SLOT_REQUIRED in errors
    }

    /** Errors are shown from the first tap on and then follow every change (see CreateRequestViewModel). */
    private fun onContinue() {
        val binding = binding ?: return
        val slotDropped = viewModel.refreshSchedule()
        val valid = viewModel.validateAddressTime()
        if (slotDropped) showMessage(R.string.create_request_slot_expired)
        if (valid) {
            navigateFromHere(R.id.cs10)
            return
        }
        val errors = viewModel.fieldErrors.value
        val addressInvalid = FieldError.ADDRESS_REQUIRED in errors || FieldError.ADDRESS_NOT_READY in errors
        val target = if (addressInvalid) binding.addressLabel else binding.timeLabel
        binding.scroll.post { scrollTo(binding, target) }
    }

    private fun scrollTo(binding: FragmentCreateRequestStep2Binding, target: View) {
        val rect = Rect()
        target.getDrawingRect(rect)
        binding.scroll.offsetDescendantRectToMyCoords(target, rect)
        val top = rect.top - resources.getDimensionPixelSize(CoreR.dimen.space_l)
        binding.scroll.smoothScrollTo(0, top.coerceAtLeast(0))
    }

    /** Ignores a second tap while the first navigation is still running. */
    private fun navigateFromHere(destination: Int, args: Bundle? = null) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.cs09) navController.navigate(destination, args)
    }

    private fun showMessage(messageRes: Int) {
        val binding = binding ?: return
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).setAnchorView(binding.continueButton).show()
    }
}
