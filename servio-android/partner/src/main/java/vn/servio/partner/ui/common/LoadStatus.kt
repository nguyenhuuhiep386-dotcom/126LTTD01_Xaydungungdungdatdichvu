package vn.servio.partner.ui.common

import android.view.View
import androidx.core.view.isVisible
import vn.servio.core.utils.UiState
import vn.servio.partner.databinding.ViewLoadStatusBinding

/** Shows the spinner or the error + "Thử lại" of [status], and [content] only when the state has data. */
object LoadStatus {
    fun bind(status: ViewLoadStatusBinding, state: UiState<*>, content: View, onRetry: () -> Unit) {
        status.root.isVisible = state !is UiState.Content
        status.progress.isVisible = state is UiState.Loading
        status.errorGroup.isVisible = state is UiState.Error
        if (state is UiState.Error) status.errorText.text = state.message
        status.retryButton.setOnClickListener { onRetry() }
        content.isVisible = state is UiState.Content
    }
}
