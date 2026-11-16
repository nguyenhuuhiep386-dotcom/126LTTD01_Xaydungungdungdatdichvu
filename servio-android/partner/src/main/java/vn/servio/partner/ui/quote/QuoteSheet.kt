package vn.servio.partner.ui.quote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.model.ScheduleType
import vn.servio.partner.databinding.SheetQuoteBinding
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.quote.QuoteSheetViewModel.Event
import vn.servio.partner.ui.quote.QuoteSheetViewModel.FieldError
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** PS-11 Gửi báo giá (bottom sheet over PS-10). On success it sets [RESULT_KEY] and closes. */
@AndroidEntryPoint
class QuoteSheet : BottomSheetDialogFragment() {

    private val viewModel: QuoteSheetViewModel by viewModels()
    private var binding: SheetQuoteBinding? = null

    override fun getTheme() = R.style.ThemeOverlay_Servio_BottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetQuoteBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.closeButton.setOnClickListener { dismiss() }
        binding.amountInput.doAfterTextChanged { updateEstimate(binding) }
        binding.dateButton.setOnClickListener { pickDate() }
        binding.timeButton.setOnClickListener { pickTime() }
        binding.sendButton.setOnClickListener {
            viewModel.submit(binding.amountInput.text?.toString().orEmpty(), binding.noteInput.text?.toString().orEmpty())
        }
        QuoteSheetViewModel.DURATIONS.forEach { minutes ->
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, binding.durationChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = getString(R.string.quote_duration_value, minutes)
            chip.tag = minutes
            chip.isChecked = minutes == viewModel.durationMinutes
            chip.setOnCheckedChangeListener { _, checked -> if (checked) viewModel.durationMinutes = minutes }
            binding.durationChips.addView(chip)
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorText.isVisible = state is UiState.Error
            binding.form.isVisible = state is UiState.Content
            if (state is UiState.Error) binding.errorText.text = state.message
            if (state is UiState.Content) {
                val request = state.data.request
                binding.requestTitle.text = request.title
                binding.windowHint.text = getString(
                    R.string.quote_window_hint,
                    Texts.schedule(requireContext(), request.scheduleType, request.scheduledStartAt, request.scheduledEndAt),
                )
                binding.windowHint.isVisible = request.scheduleType == ScheduleType.SCHEDULED
                binding.pendingInfo.text = getString(R.string.quote_pending_info, state.data.pendingQuotes, QuoteTexts.MAX_PENDING)
                if (!viewModel.isFormFilled) {
                    viewModel.isFormFilled = true
                    // Suggest the middle of the customer's budget, if any.
                    val min = request.budgetMin
                    val max = request.budgetMax
                    val suggested = when {
                        min != null && max != null -> (min + max) / 2 / 10_000 * 10_000
                        else -> max ?: min
                    }
                    suggested?.let { binding.amountInput.setText(it.toString()) }
                }
                updateEstimate(binding)
            }
        }
        collectWhenStarted(viewModel.arrival) { arrival ->
            binding.dateButton.text = Texts.dayLabel(requireContext(), arrival)
            binding.timeButton.text = Texts.time(arrival)
        }
        collectWhenStarted(viewModel.fieldErrors) { errors ->
            binding.amountLayout.error = if (FieldError.AMOUNT in errors) getString(R.string.quote_amount_invalid) else null
            binding.timeError.isVisible = FieldError.TIME in errors
        }
        collectWhenStarted(viewModel.sending) { sending -> binding.sendButton.isEnabled = !sending }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                Event.Sent -> {
                    setFragmentResult(RESULT_KEY, bundleOf())
                    dismiss()
                }
                is Event.Error -> Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun updateEstimate(binding: SheetQuoteBinding) {
        val amount = binding.amountInput.text?.toString()?.filter(Char::isDigit)?.toLongOrNull() ?: 0L
        val commission = QuoteTexts.commissionOf(amount)
        binding.commissionLabel.text = getString(R.string.quote_commission_label, QuoteTexts.COMMISSION_PERCENT)
        binding.commissionValue.text = getString(R.string.quote_minus, Money.format(commission))
        binding.netValue.text = Money.format(amount - commission)
    }

    private fun pickDate() {
        val current = viewModel.arrival.value
        val todayUtc = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.quote_pick_date)
            .setSelection(current.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointForward.from(todayUtc)).build())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            viewModel.setArrival(current.with(date))
        }
        picker.show(childFragmentManager, "date")
    }

    private fun pickTime() {
        val current: ZonedDateTime = viewModel.arrival.value
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(current.hour)
            .setMinute(current.minute)
            .setTitleText(R.string.quote_pick_time)
            .build()
        picker.addOnPositiveButtonClickListener {
            viewModel.setArrival(current.withHour(picker.hour).withMinute(picker.minute))
        }
        picker.show(childFragmentManager, "time")
    }

    companion object {
        const val RESULT_KEY = "quote_sent"
    }
}
