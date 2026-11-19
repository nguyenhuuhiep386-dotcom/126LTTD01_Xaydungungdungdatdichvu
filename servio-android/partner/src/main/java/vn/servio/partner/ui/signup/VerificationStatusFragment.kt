package vn.servio.partner.ui.signup

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.data.model.PartnerVerificationStatus
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.model.ApprovalStatus
import vn.servio.partner.data.model.DocumentType
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.databinding.FragmentVerificationStatusBinding
import vn.servio.partner.databinding.ItemStatusRowBinding
import vn.servio.partner.ui.common.LoadStatus
import javax.inject.Inject
import vn.servio.core.R as CoreR

/**
 * PS-06 Chờ duyệt. Where a partner lands after login while the profile is PENDING or REJECTED (PartnerAuthRouter).
 * APPROVED → "Bắt đầu nhận việc" opens the main tabs; REJECTED / NOT_SUBMITTED → back to the signup steps.
 */
@AndroidEntryPoint
class VerificationStatusFragment : Fragment(R.layout.fragment_verification_status) {

    @Inject lateinit var authRepository: AuthRepository

    private val viewModel: VerificationStatusViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentVerificationStatusBinding.bind(view)
        setupSignupToolbar(binding.toolbar, authRepository, showBack = false)
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.refreshButton.setOnClickListener { viewModel.load() }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = false
            // Keep the content on screen while a refresh is loading; show the full-screen state only the first time.
            val hasContent = binding.rows.childCount > 0
            if (state is UiState.Content || !hasContent) {
                LoadStatus.bind(binding.status, state, binding.swipeRefresh) { viewModel.load() }
            }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content) bind(binding, state.data)
        }
        collectWhenStarted(viewModel.approved) {
            Snackbar.make(view, R.string.verification_approved_title, Snackbar.LENGTH_LONG).show()
            leaveSignup(CoreR.id.main_graph)
        }
    }

    private fun bind(binding: FragmentVerificationStatusBinding, me: PartnerMe) {
        val status = me.verificationStatus
        binding.stateIcon.setImageResource(
            when (status) {
                PartnerVerificationStatus.APPROVED -> R.drawable.ic_check
                PartnerVerificationStatus.REJECTED -> R.drawable.ic_alert
                else -> R.drawable.ic_hourglass
            },
        )
        binding.stateTitle.setText(
            when (status) {
                PartnerVerificationStatus.APPROVED -> R.string.verification_approved_title
                PartnerVerificationStatus.REJECTED -> R.string.verification_rejected_title
                PartnerVerificationStatus.PENDING -> R.string.verification_pending_title
                PartnerVerificationStatus.NOT_SUBMITTED -> R.string.verification_not_submitted_title
            },
        )
        binding.stateSubtitle.text = when (status) {
            PartnerVerificationStatus.APPROVED -> getString(R.string.verification_approved_subtitle)
            PartnerVerificationStatus.REJECTED -> getString(R.string.verification_rejected_subtitle)
            PartnerVerificationStatus.PENDING -> getString(R.string.verification_pending_subtitle)
            PartnerVerificationStatus.NOT_SUBMITTED ->
                getString(R.string.verification_missing, me.missingForVerification.joinToString(", "))
        }

        binding.rows.removeAllViews()
        val profileComplete = me.fullName.trim().length >= 2 && me.hasAnchor
        addRow(binding.rows, getString(R.string.verification_row_profile), if (profileComplete) null else Mark.MISSING, received = profileComplete)
        listOf(
            DocumentType.ID_FRONT to R.string.kyc_id_front,
            DocumentType.ID_BACK to R.string.kyc_id_back,
            DocumentType.SELFIE_WITH_ID to R.string.kyc_selfie,
        ).forEach { (type, label) ->
            addRow(binding.rows, getString(label), me.document(type)?.status?.let(::markOf) ?: Mark.MISSING)
        }
        me.skills.forEach { skill -> addRow(binding.rows, skill.categoryName, markOf(skill.status)) }

        val rejectedDocuments = me.documents.filter { it.status == ApprovalStatus.REJECTED }
        val note = buildList {
            me.verificationNote?.takeIf { it.isNotBlank() }?.let { add(getString(R.string.verification_reason, it)) }
            rejectedDocuments.mapNotNull { it.rejectReason }.forEach { add(it) }
        }
        binding.noteCard.isVisible = status == PartnerVerificationStatus.REJECTED || rejectedDocuments.isNotEmpty()
        binding.noteTitle.setText(R.string.verification_note_title)
        binding.noteText.text = note.joinToString("\n").ifEmpty { getString(R.string.verification_note_default) }

        val button = binding.actionBar.primaryButton
        when (status) {
            PartnerVerificationStatus.APPROVED -> {
                button.setText(R.string.verification_start)
                button.setOnClickListener { leaveSignup(CoreR.id.main_graph) }
            }
            PartnerVerificationStatus.PENDING -> {
                button.setText(R.string.verification_refresh)
                button.setOnClickListener { viewModel.load() }
            }
            PartnerVerificationStatus.REJECTED, PartnerVerificationStatus.NOT_SUBMITTED -> {
                button.setText(R.string.verification_fix)
                // KYC is the usual reason; the earlier steps are one tap back from PS-05.
                button.setOnClickListener { leaveSignupTo(firstIncompleteStep(me)) }
            }
        }
        binding.refreshButton.isVisible = status != PartnerVerificationStatus.PENDING
    }

    /** Opens the first signup step that still lacks data, keeping the previous steps under it for "back". */
    private fun leaveSignupTo(step: Int) {
        val steps = listOf(R.id.ps02, R.id.ps03, R.id.ps04, R.id.ps05)
        leaveSignup(R.id.ps02)
        steps.drop(1).takeWhile { steps.indexOf(it) <= steps.indexOf(step) }.forEach { openSignupStep(it) }
    }

    private fun firstIncompleteStep(me: PartnerMe): Int = when {
        me.fullName.trim().length < 2 -> R.id.ps02
        me.skills.isEmpty() -> R.id.ps03
        !me.hasAnchor -> R.id.ps04
        else -> R.id.ps05
    }

    private enum class Mark(val textRes: Int, @param:ColorRes val colorRes: Int, val iconRes: Int) {
        RECEIVED(R.string.status_received, CoreR.color.servio_success, R.drawable.ic_check),
        PENDING(R.string.status_pending, CoreR.color.servio_text_accent, R.drawable.ic_hourglass),
        APPROVED(R.string.status_approved, CoreR.color.servio_success, R.drawable.ic_check),
        REJECTED(R.string.status_resend, CoreR.color.servio_error, R.drawable.ic_alert),
        MISSING(R.string.status_missing, CoreR.color.servio_text_secondary, R.drawable.ic_alert),
    }

    private fun markOf(status: ApprovalStatus) = when (status) {
        ApprovalStatus.PENDING -> Mark.PENDING
        ApprovalStatus.APPROVED -> Mark.APPROVED
        ApprovalStatus.REJECTED -> Mark.REJECTED
    }

    private fun addRow(parent: ViewGroup, label: String, mark: Mark?, received: Boolean = false) {
        val row = ItemStatusRowBinding.inflate(layoutInflater, parent, false)
        val shown = mark ?: if (received) Mark.RECEIVED else Mark.MISSING
        val color = ContextCompat.getColor(requireContext(), shown.colorRes)
        row.label.text = label
        row.value.setText(shown.textRes)
        row.value.setTextColor(color)
        row.value.setCompoundDrawablesRelativeWithIntrinsicBounds(shown.iconRes, 0, 0, 0)
        row.value.compoundDrawablesRelative[0]?.setTint(color)
        parent.addView(row.root)
    }
}
