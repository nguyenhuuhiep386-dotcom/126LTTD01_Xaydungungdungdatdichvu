package vn.servio.partner.ui.earnings

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.image.CameraCaptureFiles
import vn.servio.partner.databinding.FragmentEarningsBinding
import vn.servio.partner.databinding.ItemStatusRowBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.PhotoPicker
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.common.bindPhotoStrip
import vn.servio.partner.ui.earnings.EarningsViewModel.Event
import javax.inject.Inject
import vn.servio.core.R as CoreR

/** PS-21 Thu nhập & công nợ (tab "Thu nhập"), with the debt payment form (PS-21a) inline. */
@AndroidEntryPoint
class EarningsFragment : Fragment(R.layout.fragment_earnings) {

    @Inject lateinit var captureFiles: CameraCaptureFiles

    private val viewModel: EarningsViewModel by viewModels()
    private val photoPicker = PhotoPicker(this, { captureFiles }) { uri -> viewModel.addEvidence(uri) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentEarningsBinding.bind(view)
        binding.notificationsButton.setOnClickListener { findNavController().navigate(R.id.ps28) }
        binding.profileButton.setOnClickListener { findNavController().navigate(R.id.ps23) }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(showSpinner = false) }
        binding.declareButton.setOnClickListener { binding.declareForm.isVisible = !binding.declareForm.isVisible }
        binding.sendDeclareButton.setOnClickListener {
            val reference = binding.referenceInput.text?.toString().orEmpty()
            binding.referenceLayout.error =
                if (reference.trim().length < EarningsViewModel.MIN_REFERENCE) getString(R.string.earnings_reference_invalid) else null
            if (viewModel.evidence.urls.isEmpty()) Snackbar.make(view, R.string.earnings_evidence_missing, Snackbar.LENGTH_LONG).show()
            viewModel.declare(reference)
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = false
            LoadStatus.bind(binding.status, state, binding.swipeRefresh) { viewModel.load() }
            if (state !is UiState.Content) return@collectWhenStarted
            val content = state.data
            binding.todayAmount.text = Money.format(content.todayEarning)
            binding.todayOrders.text =
                getString(R.string.earnings_today_detail, content.todayOrders, Money.format(content.weekEarning))

            val wallet = content.wallet
            binding.debtAmount.text = getString(R.string.earnings_debt_amount, Money.format(wallet.debtBalance), Money.format(wallet.maxDebt))
            binding.debtProgress.max = wallet.maxDebt.coerceAtLeast(1).toInt()
            binding.debtProgress.progress = wallet.debtBalance.coerceAtMost(wallet.maxDebt).toInt()
            val blocked = wallet.debtBalance > wallet.maxDebt
            binding.debtProgress.setIndicatorColor(
                requireContext().getColor(if (blocked) CoreR.color.servio_error else CoreR.color.servio_text_accent),
            )
            binding.debtNote.text = when {
                content.pendingDeclared || wallet.pendingPayment > 0 ->
                    getString(R.string.earnings_pending, Money.format(wallet.pendingPayment))
                blocked -> getString(R.string.earnings_blocked)
                else -> getString(R.string.earnings_debt_note, Money.format(wallet.maxDebt))
            }
            val canDeclare = wallet.debtBalance > 0 && !content.pendingDeclared
            binding.declareButton.isVisible = canDeclare
            if (!canDeclare) binding.declareForm.isVisible = false
            binding.declareHint.text = getString(R.string.earnings_declare_hint, Money.format(wallet.debtBalance))

            binding.ledger.removeAllViews()
            binding.ledgerEmpty.isVisible = content.transactions.isEmpty()
            content.transactions.forEach { line ->
                val row = ItemStatusRowBinding.inflate(layoutInflater, binding.ledger, false)
                row.label.text = getString(R.string.earnings_ledger_line, line.description, Texts.dateTime(line.createdAt))
                val sign = if (line.direction == "DEBIT") "−" else "+"
                row.value.text = sign + Money.format(line.amount)
                binding.ledger.addView(row.root)
            }
        }
        collectWhenStarted(viewModel.evidence.photos.combine(viewModel.sending) { photos, sending -> photos to sending }) { (photos, sending) ->
            bindPhotoStrip(binding.evidenceStrip, photos, viewModel.evidence.max, onAdd = { photoPicker.show() },
                onRemove = viewModel.evidence::remove)
            binding.sendDeclareButton.isEnabled = !sending && photos.none { it.uploading }
        }
        collectWhenStarted(viewModel.evidence.errors) { error ->
            if (error != null) {
                Snackbar.make(view, error, Snackbar.LENGTH_LONG).show()
                viewModel.evidence.consumeError()
            }
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Message -> Snackbar.make(view, event.text, Snackbar.LENGTH_LONG).show()
                Event.Declared -> {
                    binding.declareForm.isVisible = false
                    binding.referenceInput.setText("")
                    Snackbar.make(view, R.string.earnings_declared, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load(showSpinner = false)
    }
}
