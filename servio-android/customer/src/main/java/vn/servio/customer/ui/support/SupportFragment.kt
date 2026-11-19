package vn.servio.customer.ui.support

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.databinding.FragmentSupportBinding
import vn.servio.customer.databinding.ItemSupportFaqBinding
import vn.servio.customer.ui.order.OrderFormat

/**
 * CS-34 Trung tâm hỗ trợ (Figma 18:3388): hotline card and the frequently asked questions, all from #107.
 * "Gọi hotline" opens the dialer with the number (ACTION_DIAL needs no permission; nothing is called by itself).
 */
@AndroidEntryPoint
class SupportFragment : Fragment(R.layout.fragment_support) {

    private val viewModel: SupportViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSupportBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorGroup.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.errorText.text = state.message
                is UiState.Content -> binding.show(state.data)
            }
        }
    }

    private fun FragmentSupportBinding.show(config: ServerConfig) {
        val contact = SupportTexts.contact(config.support)
        contactCard.isVisible = contact != null
        val hotline = contact?.hotline
        hotlineText.isVisible = hotline != null
        hotlineText.text = hotline
        callButton.isVisible = hotline != null
        callButton.setOnClickListener { hotline?.let(::dial) }
        hoursText.isVisible = contact?.hours != null
        hoursText.text = contact?.hours

        faqList.removeAllViews()
        SupportTexts.faqs(config).forEach { faq ->
            val item = ItemSupportFaqBinding.inflate(layoutInflater, faqList, true)
            item.question.setText(faq.question)
            item.answer.text = getString(faq.answer, *faq.args.map(::format).toTypedArray())
        }
    }

    private fun format(arg: SupportTexts.Arg): Any = when (arg) {
        is SupportTexts.Arg.Money -> OrderFormat.money(requireContext(), arg.amount)
        is SupportTexts.Arg.Count -> arg.value
        is SupportTexts.Arg.Text -> arg.value
    }

    /** The dialer with the number filled in; the customer presses call there. No dialer app (tablets): a message. */
    private fun dial(hotline: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + SupportTexts.dialNumber(hotline)))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            view?.let { Snackbar.make(it, R.string.support_dial_unavailable, Snackbar.LENGTH_LONG).show() }
        }
    }
}
