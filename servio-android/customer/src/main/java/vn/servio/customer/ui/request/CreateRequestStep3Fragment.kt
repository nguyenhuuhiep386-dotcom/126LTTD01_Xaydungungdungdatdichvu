package vn.servio.customer.ui.request

import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import vn.servio.core.R as CoreR
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentCreateRequestStep3Binding
import vn.servio.customer.ui.request.CreateRequestViewModel.FieldError
import java.text.NumberFormat
import java.util.Locale

/**
 * CS-10 Tạo yêu cầu – Bước 3 Ngân sách và yêu cầu: a budget range or "Để người làm báo giá", the reference price of
 * the service, three requirements for the partner and the search radius. "Xem lại" opens CS-11.
 * Back returns to CS-09 without asking: everything lives in [CreateRequestViewModel].
 */
@AndroidEntryPoint
class CreateRequestStep3Fragment : Fragment(R.layout.fragment_create_request_step3) {

    private val viewModel: CreateRequestViewModel by navGraphViewModels(R.id.create_request_graph) {
        defaultViewModelProviderFactory
    }

    private var binding: FragmentCreateRequestStep3Binding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCreateRequestStep3Binding.bind(view)
        this.binding = binding
        CreateRequestSteps.bind(binding.steps, step = 3, label = R.string.create_request_step_budget)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.budgetGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.setQuoteByPartner(checkedId == R.id.budgetQuoteRadio)
        }
        binding.budgetMinInput.addTextChangedListener(MoneyWatcher(binding.budgetMinInput, viewModel::setBudgetMin))
        binding.budgetMaxInput.addTextChangedListener(MoneyWatcher(binding.budgetMaxInput, viewModel::setBudgetMax))

        binding.experienceCheck.text = getString(R.string.create_request_require_experience, RequestDraft.EXPERIENCE_YEARS)
        binding.ratingCheck.text = getString(R.string.create_request_require_rating, RATING_FORMAT.format(RequestDraft.MIN_RATING))
        // Listeners fire on every isChecked change, including the ones made by bind: the ViewModel ignores no-ops.
        binding.experienceCheck.setOnCheckedChangeListener { _, checked -> viewModel.setRequireExperience(checked) }
        binding.certificateCheck.setOnCheckedChangeListener { button, checked ->
            if (button.isEnabled) viewModel.setRequireCertificate(checked)
        }
        binding.ratingCheck.setOnCheckedChangeListener { _, checked -> viewModel.setRequireHighRating(checked) }

        val radiusAdapter = ScheduleChipAdapter(fillWidth = false) { viewModel.setSearchRadius(it.key.toInt()) }
        binding.radiusList.adapter = radiusAdapter
        // Same as the CS-09 chips: the change animation would fight the checked look.
        binding.radiusList.itemAnimator = null

        binding.reviewButton.setOnClickListener { onReview() }

        // CS-09 shares the error set: bring back this step's errors if it was already checked.
        viewModel.recheckBudget()

        collectWhenStarted(combine(viewModel.draft, viewModel.category, ::Pair)) { (draft, category) ->
            binding.bindDraft(draft, category, radiusAdapter)
        }
        collectWhenStarted(viewModel.fieldErrors) { errors -> binding.bindErrors(errors) }
    }

    /** A notice left by CS-11 when it sent the customer back here. */
    override fun onResume() {
        super.onResume()
        val binding = binding ?: return
        viewModel.takeNotice()?.let { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setAnchorView(binding.reviewButton).show()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun FragmentCreateRequestStep3Binding.bindDraft(
        draft: RequestDraft,
        category: UiState<CategoryChoice>,
        radiusAdapter: ScheduleChipAdapter,
    ) {
        val checkedRadio = if (draft.quoteByPartner) R.id.budgetQuoteRadio else R.id.budgetRangeRadio
        if (budgetGroup.checkedRadioButtonId != checkedRadio) budgetGroup.check(checkedRadio)
        // "Để người làm báo giá" keeps the numbers, greyed out, for when the customer switches back.
        val rangeEnabled = !draft.quoteByPartner
        budgetMinLayout.isEnabled = rangeEnabled
        budgetMaxLayout.isEnabled = rangeEnabled
        budgetMinLabel.alpha = if (rangeEnabled) 1f else DISABLED_ALPHA
        budgetMaxLabel.alpha = if (rangeEnabled) 1f else DISABLED_ALPHA
        budgetMinInput.showAmount(draft.budgetMin)
        budgetMaxInput.showAmount(draft.budgetMax)

        val choice = (category as? UiState.Content)?.data
        val range = choice?.let { referenceRange(it.service) }
        referenceCard.isVisible = range != null
        if (choice != null && range != null) {
            referenceText.text = getString(
                R.string.create_request_reference_price,
                choice.service.name,
                MoneyInput.format(range.min),
                MoneyInput.format(range.max),
            )
        }

        experienceCheck.isChecked = draft.requireExperience
        val certificate = viewModel.certificateFor(draft, category)
        // Enabled first so the listener (which skips a locked box) never records the forced tick as the customer's.
        certificateCheck.isEnabled = !certificate.locked
        certificateCheck.isChecked = certificate.checked
        certificateNote.isVisible = certificate.locked
        ratingCheck.isChecked = draft.requireHighRating

        radiusAdapter.submitList(
            RequestDraft.RADIUS_OPTIONS_KM.map { km ->
                ScheduleChipAdapter.Item(
                    key = km.toString(),
                    label = getString(R.string.create_request_radius_option, km),
                    enabled = true,
                    selected = km == draft.searchRadiusKm,
                )
            },
        )
    }

    /** Only when the box does not already show [amount]: rewriting it while typing would move the cursor. */
    private fun EditText.showAmount(amount: Long?) {
        if (MoneyInput.parse(text) == amount) return
        val formatted = amount?.let(MoneyInput::format).orEmpty()
        setText(formatted)
        setSelection(formatted.length)
    }

    private fun FragmentCreateRequestStep3Binding.bindErrors(errors: Set<FieldError>) {
        val minMissing = FieldError.BUDGET_MIN_MISSING in errors
        val maxMissing = FieldError.BUDGET_MAX_MISSING in errors
        val message = when {
            minMissing && maxMissing -> R.string.create_request_budget_both_missing
            minMissing -> R.string.create_request_budget_min_missing
            maxMissing -> R.string.create_request_budget_max_missing
            FieldError.BUDGET_RANGE in errors -> R.string.create_request_budget_range_error
            else -> null
        }
        budgetError.isVisible = message != null
        if (message != null) budgetError.setText(message)
    }

    /** "Xem lại" is always enabled; errors are shown from the first tap on (see CreateRequestViewModel). */
    private fun onReview() {
        val binding = binding ?: return
        if (viewModel.validateBudget()) {
            val navController = findNavController()
            if (navController.currentDestination?.id == R.id.cs10) navController.navigate(R.id.cs11)
            return
        }
        binding.scroll.post { scrollTo(binding, binding.budgetLabel) }
    }

    private fun scrollTo(binding: FragmentCreateRequestStep3Binding, target: View) {
        val rect = Rect()
        target.getDrawingRect(rect)
        binding.scroll.offsetDescendantRectToMyCoords(target, rect)
        val top = rect.top - resources.getDimensionPixelSize(CoreR.dimen.space_l)
        binding.scroll.smoothScrollTo(0, top.coerceAtLeast(0))
    }

    /**
     * Groups the digits as they are typed (300000 → 300.000) through [MoneyInput.reformat], keeping the cursor among
     * the same digits, and reports the parsed amount. An edit over 9 digits is undone.
     */
    private class MoneyWatcher(private val input: EditText, private val onAmount: (Long?) -> Unit) : TextWatcher {
        private var previous = ""
        private var previousCursor = 0
        private var rewriting = false

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (rewriting) return
            previous = s.toString()
            previousCursor = input.selectionEnd.coerceAtLeast(0)
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable) {
            if (rewriting) return
            val edit = MoneyInput.reformat(previous, s, input.selectionEnd.coerceAtLeast(0))
            val text = edit?.text ?: previous
            val cursor = edit?.cursor ?: previousCursor
            if (text != s.toString()) {
                rewriting = true
                s.replace(0, s.length, text)
                rewriting = false
            }
            input.setSelection(cursor.coerceIn(0, s.length))
            onAmount(MoneyInput.parse(s))
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f

        /** "4,5" whatever the device language. */
        val RATING_FORMAT: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
    }
}
