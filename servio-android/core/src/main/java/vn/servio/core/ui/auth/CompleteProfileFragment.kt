package vn.servio.core.ui.auth

import android.os.Bundle
import android.view.View
import androidx.core.view.isInvisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.R
import vn.servio.core.databinding.FragmentCompleteProfileBinding
import vn.servio.core.utils.AuthRouter
import vn.servio.core.utils.collectWhenStarted
import javax.inject.Inject

/** CS-05: first-time name entry (#10), shown when needsProfileCompletion is true. */
@AndroidEntryPoint
class CompleteProfileFragment : Fragment(R.layout.fragment_complete_profile) {

    @Inject lateinit var authRouter: AuthRouter

    private val viewModel: CompleteProfileViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCompleteProfileBinding.bind(view)
        binding.nameInput.doAfterTextChanged { binding.nameLayout.error = null }
        binding.saveButton.setOnClickListener { viewModel.save(binding.nameInput.text?.toString().orEmpty()) }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isInvisible = !state.isLoading
            binding.saveButton.isEnabled = !state.isLoading
            binding.nameLayout.error = when (state.error) {
                null -> null
                CompleteProfileViewModel.INVALID_NAME -> getString(R.string.profile_name_invalid)
                else -> state.error
            }
        }
        collectWhenStarted(viewModel.saved) { user ->
            navigateAfterLogin(authRouter.destinationAfterLogin(user))
        }
    }
}
