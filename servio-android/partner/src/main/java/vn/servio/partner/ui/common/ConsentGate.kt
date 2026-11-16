package vn.servio.partner.ui.common

import android.content.Intent
import android.net.Uri
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import vn.servio.core.BuildConfig
import vn.servio.core.network.ApiResult
import vn.servio.partner.R
import vn.servio.partner.data.repository.ConsentPurpose
import vn.servio.partner.data.repository.ConsentRepository

/**
 * Runs [onGranted] once every consent in [purposes] is on record. Missing ones are asked with an explicit, unticked
 * checkbox (Điều 9 Luật 91/2025); "Đồng ý" is enabled only after the user ticks it.
 */
fun Fragment.withConsent(consents: ConsentRepository, purposes: List<ConsentPurpose>, onGranted: () -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        val missing = consents.missing(purposes)
        if (missing.isEmpty()) {
            onGranted()
            return@launch
        }
        val legal = consents.legalInfo()
        val context = requireContext()
        val padding = resources.getDimensionPixelSize(vn.servio.core.R.dimen.screen_padding)
        val body = TextView(context).apply {
            text = missing.joinToString("\n\n") { getString(textOf(it)) }
            setTextAppearance(vn.servio.core.R.style.TextAppearance_Servio_Body)
        }
        val check = CheckBox(context).apply { text = getString(R.string.consent_checkbox) }
        val links = TextView(context).apply {
            text = getString(R.string.consent_read_policy)
            setTextAppearance(vn.servio.core.R.style.TextAppearance_Servio_Accent)
            setPadding(0, padding / 2, 0, 0)
            setOnClickListener {
                val path = legal?.privacyPolicyUrl?.trimStart('/') ?: return@setOnClickListener
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.BASE_URL + path))) }
            }
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(body)
            if (legal?.privacyPolicyUrl?.isNotBlank() == true) addView(links)
            addView(check)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.consent_title)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.consent_agree) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val result = consents.grant(missing)) {
                        is ApiResult.Success -> onGranted()
                        is ApiResult.Failure -> view?.let { Snackbar.make(it, result.message, Snackbar.LENGTH_LONG).show() }
                    }
                }
            }
            .show()
        val agree = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        agree.isEnabled = false
        check.setOnCheckedChangeListener { _, checked -> agree.isEnabled = checked }
    }
}

private fun textOf(purpose: ConsentPurpose) = when (purpose) {
    ConsentPurpose.KYC_IDENTITY -> R.string.consent_kyc
    ConsentPurpose.LOCATION_SHARING -> R.string.consent_location
    ConsentPurpose.TERMS_AND_PRIVACY -> R.string.consent_terms
}
