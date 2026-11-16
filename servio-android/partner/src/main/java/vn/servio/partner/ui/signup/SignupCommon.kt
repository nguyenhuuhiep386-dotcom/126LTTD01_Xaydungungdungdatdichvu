package vn.servio.partner.ui.signup

import android.content.res.ColorStateList
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.ui.auth.navigateToLogin
import vn.servio.partner.R
import vn.servio.partner.databinding.ViewSignupStepsBinding

/** Step bar of the partner signup (PS-02 → PS-05). */
object SignupSteps {
    const val COUNT = 4

    fun bind(binding: ViewSignupStepsBinding, step: Int, @StringRes nameRes: Int) {
        val context = binding.root.context
        val done = ContextCompat.getColor(context, R.color.signup_step_done)
        val todo = ContextCompat.getColor(context, vn.servio.core.R.color.servio_divider)
        listOf(binding.segment1, binding.segment2, binding.segment3, binding.segment4).forEachIndexed { index, segment ->
            segment.backgroundTintList = ColorStateList.valueOf(if (index < step) done else todo)
        }
        binding.stepLabel.text = context.getString(R.string.signup_step_label, step, COUNT, context.getString(nameRes))
    }
}

/**
 * Signup screens sit outside main_graph and the auth screens are gone from the back stack,
 * so every step offers "Đăng xuất" in the toolbar (otherwise a partner could not switch account).
 */
fun Fragment.setupSignupToolbar(toolbar: MaterialToolbar, authRepository: AuthRepository, showBack: Boolean = true) {
    if (showBack) {
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationContentDescription(R.string.action_back)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }
    toolbar.inflateMenu(R.menu.signup_actions)
    toolbar.setOnMenuItemClickListener { item ->
        if (item.itemId == R.id.action_logout) {
            viewLifecycleOwner.lifecycleScope.launch {
                authRepository.logout()
                findNavController().navigateToLogin()
            }
            true
        } else {
            false
        }
    }
}

/** Opens the next signup step. */
fun Fragment.openSignupStep(@IdRes destination: Int) {
    findNavController().navigate(destination)
}

/** Leaves the signup flow for [destination] (PS-06 or the main tabs) and clears the signup back stack. */
fun Fragment.leaveSignup(@IdRes destination: Int) {
    findNavController().navigate(
        destination,
        null,
        navOptions { popUpTo(R.id.nav_root) { inclusive = true } },
    )
}
