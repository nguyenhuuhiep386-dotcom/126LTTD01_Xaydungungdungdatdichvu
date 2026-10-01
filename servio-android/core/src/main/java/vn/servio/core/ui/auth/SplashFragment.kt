package vn.servio.core.ui.auth

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.R
import vn.servio.core.databinding.FragmentSplashBinding
import vn.servio.core.utils.AuthRouter
import vn.servio.core.utils.collectWhenStarted
import javax.inject.Inject

/** CS-01 / PS-01: restores the session (GET /users/me) and routes to login or the app. */
@AndroidEntryPoint
class SplashFragment : Fragment(R.layout.fragment_splash) {

    @Inject lateinit var authRouter: AuthRouter

    private val viewModel: SplashViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSplashBinding.bind(view)
        binding.appName.text = requireContext().applicationInfo.loadLabel(requireContext().packageManager)
        binding.retryButton.setOnClickListener { viewModel.check() }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is SplashViewModel.State.Checking
            binding.errorGroup.isVisible = state is SplashViewModel.State.Error
            when (state) {
                SplashViewModel.State.Checking -> Unit
                SplashViewModel.State.NeedsLogin ->
                    findNavController().navigate(R.id.action_splash_to_phoneInput)
                is SplashViewModel.State.LoggedIn ->
                    navigateAfterLogin(authRouter.destinationAfterLogin(state.user))
                is SplashViewModel.State.Error -> binding.errorText.text = state.message
            }
        }
    }
}
