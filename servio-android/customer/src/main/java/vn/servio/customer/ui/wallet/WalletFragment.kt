package vn.servio.customer.ui.wallet

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.Debt
import vn.servio.customer.databinding.FragmentWalletBinding
import vn.servio.customer.databinding.ItemDebtBinding
import vn.servio.customer.ui.order.OrderFormat
import vn.servio.customer.ui.request.AddImageSheet
import vn.servio.customer.ui.request.ImageThumbnails
import vn.servio.customer.ui.wallet.WalletForm.EvidenceStatus
import vn.servio.customer.ui.wallet.WalletViewModel.Event
import vn.servio.customer.ui.wallet.WalletViewModel.Evidence
import vn.servio.customer.ui.wallet.WalletViewModel.State

/** CS-30 Phí huỷ và giao dịch (#87, #155, #156). The picking of the receipt is the one of CS-08 (camera or gallery). */
@AndroidEntryPoint
class WalletFragment : Fragment(R.layout.fragment_wallet) {

    private val viewModel: WalletViewModel by viewModels()

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        viewModel.onCaptureResult(saved)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else showMessage(R.string.create_request_camera_denied)
    }

    private val pickImage = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickEvidence(uri)
    }

    private var binding: FragmentWalletBinding? = null
    private var thumbnails: ImageThumbnails? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWalletBinding.bind(view)
        this.binding = binding
        thumbnails = ImageThumbnails(requireContext().contentResolver, viewLifecycleOwner.lifecycleScope, THUMBNAIL_EDGE)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }

        // The ViewModel is the only source of the text (rotation and process death included).
        binding.referenceInput.setText(viewModel.state.value.reference)
        binding.referenceInput.doAfterTextChanged { viewModel.setReference(it?.toString().orEmpty()) }

        binding.addEvidenceButton.setOnClickListener { chooseImage() }
        binding.changeEvidenceButton.setOnClickListener { chooseImage() }
        binding.removeEvidenceButton.setOnClickListener { viewModel.removeEvidence() }
        binding.evidenceCard.setOnClickListener { viewModel.retryEvidence() }
        childFragmentManager.setFragmentResultListener(AddImageSheet.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            when (result.getString(AddImageSheet.RESULT_SOURCE)) {
                AddImageSheet.Source.CAMERA.name -> openCamera()
                AddImageSheet.Source.GALLERY.name -> pickImage.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            }
        }

        binding.submitButton.setOnClickListener { viewModel.submit() }

        binding.bankRow.label.setText(R.string.wallet_transfer_bank)
        binding.accountRow.label.setText(R.string.wallet_transfer_account)
        binding.holderRow.label.setText(R.string.wallet_transfer_holder)
        binding.noteRow.label.setText(R.string.wallet_transfer_note)
        binding.accountRow.value.setOnClickListener {
            viewModel.state.value.transfer?.let { copy(R.string.wallet_transfer_account, it.accountNumber) }
        }
        binding.noteRow.value.setOnClickListener {
            viewModel.state.value.transfer?.note?.let { copy(R.string.wallet_transfer_note, it) }
        }

        collectWhenStarted(viewModel.state) { state -> binding.bind(state) }
        collectWhenStarted(viewModel.state.map { it.debts }.distinctUntilChanged()) { debts -> debts?.let { binding.bindDebts(it) } }
        collectWhenStarted(viewModel.state.map { it.evidence }.distinctUntilChanged()) { evidence -> binding.bindEvidence(evidence) }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Sent -> {
                    binding.referenceInput.setText("")
                    showMessage(getString(R.string.wallet_sent, OrderFormat.money(requireContext(), event.amount)))
                }
                is Event.Message -> event.res?.let(::showMessage) ?: event.text?.let(::showMessage)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        thumbnails = null
        super.onDestroyView()
    }

    private fun FragmentWalletBinding.bind(state: State) {
        val wallet = state.wallet
        val debts = state.debts
        progress.isVisible = state.loading && !state.loaded
        errorGroup.isVisible = !state.loaded && state.error != null
        errorText.text = state.error
        content.isVisible = state.loaded
        if (wallet == null || debts == null) {
            bottomBar.isVisible = false
            return
        }
        val context = requireContext()

        dueAmount.text = OrderFormat.money(context, wallet.debtBalance)
        limitNote.isVisible = wallet.maxDebt != null
        wallet.maxDebt?.let { limitNote.text = getString(R.string.wallet_limit_note, OrderFormat.money(context, it)) }
        pendingNote.isVisible = wallet.pendingPayment > 0
        pendingNote.text = getString(R.string.wallet_pending_note, OrderFormat.money(context, wallet.pendingPayment))

        debtsCard.isVisible = debts.isNotEmpty()
        noDebtsText.isVisible = debts.isEmpty()

        val owes = state.status == WalletForm.Status.OWES
        declareGroup.isVisible = owes
        bindTransfer(state.transfer)
        bottomBar.isVisible = owes
        referenceLayout.error = when {
            state.referenceServerError != null -> state.referenceServerError
            state.referenceTooShort -> getString(R.string.wallet_reference_length)
            else -> null
        }
        referenceInput.isEnabled = !state.sending
        submitButton.isEnabled = state.canSubmit
    }

    /** Hidden while #107 has no complete account (AW-15); the note row also needs the phone number of #9. */
    private fun FragmentWalletBinding.bindTransfer(transfer: TransferCard?) {
        transferCard.isVisible = transfer != null
        if (transfer == null) return
        bankRow.value.text = transfer.bankName
        accountRow.value.text = transfer.accountNumber
        holderRow.value.text = transfer.accountHolder
        noteRow.root.isVisible = transfer.note != null
        noteRow.value.text = transfer.note
    }

    /**
     * A tap on the account number or the note: to the clipboard. From Android 13 (API 33) the system confirms the copy
     * itself, so "Đã sao chép" is shown only up to Android 12L.
     */
    private fun copy(@StringRes label: Int, text: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(label), text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) showMessage(R.string.wallet_copied)
    }

    private fun FragmentWalletBinding.bindDebts(debts: List<Debt>) {
        debtRows.removeAllViews()
        val inflater = layoutInflater
        debts.forEachIndexed { index, debt ->
            if (index > 0) {
                debtRows.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = resources.getDimensionPixelSize(vn.servio.core.R.dimen.space_l)
                        marginEnd = marginStart
                    }
                    setBackgroundColor(requireContext().getColor(vn.servio.core.R.color.servio_divider))
                })
            }
            val row = ItemDebtBinding.inflate(inflater, debtRows, true)
            row.title.text = debt.orderCode?.takeIf { it.isNotEmpty() }
                ?.let { getString(R.string.wallet_debt_title, it, debt.description) }
                ?: debt.description
            val day = WalletForm.day(debt.createdAt)
            row.day.isVisible = day != null
            row.day.text = day
            row.amount.text = OrderFormat.money(requireContext(), debt.amount)
            bindChip(row, debt.isDeclared)
        }
    }

    /** "Chưa trả" in the red of a dispute, "Đang chờ xác nhận" in the pale yellow of an order that moves on. */
    private fun bindChip(row: ItemDebtBinding, declared: Boolean) {
        val context = requireContext()
        val (label, text, background) = if (declared) {
            Triple(R.string.wallet_debt_pending, vn.servio.core.R.color.servio_text_accent, vn.servio.core.R.color.servio_primary_container)
        } else {
            Triple(R.string.wallet_debt_unpaid, vn.servio.core.R.color.servio_error, vn.servio.core.R.color.servio_error_container)
        }
        row.chip.setText(label)
        row.chip.setTextColor(context.getColor(text))
        row.chip.backgroundTintList = ColorStateList.valueOf(context.getColor(background))
    }

    private fun FragmentWalletBinding.bindEvidence(evidence: Evidence?) {
        addEvidenceButton.isVisible = evidence == null
        evidenceGroup.isVisible = evidence != null
        if (evidence == null) {
            evidenceImage.setImageDrawable(null)
            return
        }
        thumbnails?.load(Uri.parse(evidence.uri), evidenceImage)
        val uploading = evidence.status == EvidenceStatus.UPLOADING
        val failed = evidence.status == EvidenceStatus.FAILED
        evidenceScrim.isVisible = uploading || failed
        evidenceProgress.isVisible = uploading
        evidenceFailed.isVisible = failed
        evidenceCard.isClickable = failed
        evidenceError.isVisible = failed && evidence.error != null
        evidenceError.text = evidence.error
    }

    // region Image picking (same flow as CS-08)

    private fun chooseImage() {
        val hasCamera = requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        AddImageSheet.show(childFragmentManager, showCamera = hasCamera)
    }

    /** As on CS-08: CAMERA is not declared, so TakePicture needs no grant unless a library adds the permission. */
    private fun openCamera() {
        if (declaresCameraPermission() &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            launchCamera()
        }
    }

    private fun declaresCameraPermission(): Boolean {
        val context = requireContext()
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull()
        return info?.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }

    private fun launchCamera() {
        val uri = viewModel.startCapture()
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            viewModel.onCaptureResult(false)
            showMessage(R.string.create_request_camera_unavailable)
        }
    }

    // endregion

    private fun showMessage(@StringRes res: Int) = showMessage(getString(res))

    private fun showMessage(text: String) {
        val view = binding?.root ?: return
        Snackbar.make(view, text, Snackbar.LENGTH_LONG).apply {
            binding?.bottomBar?.takeIf { it.isVisible }?.let { anchorView = it }
        }.show()
    }

    private companion object {
        const val THUMBNAIL_EDGE = 480
    }
}
