package vn.servio.core.ui.placeholder

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import vn.servio.core.R
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.databinding.FragmentPlaceholderBinding
import vn.servio.core.ui.auth.navigateToLogin
import javax.inject.Inject

/**
 * Stand-in for a screen that is not built yet. Configured entirely from the nav graph:
 *
 * ```xml
 * <fragment android:id="@+id/cs08" android:name="vn.servio.core.ui.placeholder.PlaceholderFragment">
 *     <argument android:name="code" android:defaultValue="CS-08" />
 *     <argument android:name="title" android:defaultValue="Tạo yêu cầu — B1 Mô tả" />
 *     <argument android:name="description" android:defaultValue="Tiêu đề, mô tả, ảnh" />
 *     <argument android:name="links" android:defaultValue="cs09|Bước 2: Địa chỉ &amp; thời gian" />
 * </fragment>
 * ```
 * `links` is a `;`-separated list of `destinationId|Button label`. To build the real screen,
 * create a Fragment and change only `android:name` (keep the id so navigation keeps working).
 */
@AndroidEntryPoint
class PlaceholderFragment : Fragment(R.layout.fragment_placeholder) {

    @Inject lateinit var authRepository: AuthRepository

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPlaceholderBinding.bind(view)
        val args = requireArguments()
        binding.code.text = args.getString("code")
        binding.title.text = args.getString("title")
        binding.description.text = args.getString("description")
        binding.description.isVisible = !args.getString("description").isNullOrBlank()

        val links = parseLinks(args.getString("links"))
        binding.linksTitle.isVisible = links.isNotEmpty()
        links.forEach { (destination, label) -> binding.links.addView(linkButton(binding.links, destination, label)) }

        binding.logoutButton.isVisible = args.getBoolean("showLogout")
        binding.logoutButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                authRepository.logout()
                findNavController().navigateToLogin()
            }
        }
    }

    // ponytail: resolves destination ids by name; fine for scaffolding, removed screen by screen.
    @SuppressLint("DiscouragedApi")
    private fun linkButton(parent: ViewGroup, destinationName: String, label: String): MaterialButton {
        val destinationId = resources.getIdentifier(destinationName, "id", requireContext().packageName)
        val button = layoutInflater.inflate(R.layout.item_placeholder_link, parent, false) as MaterialButton
        return button.apply {
            text = label
            isEnabled = destinationId != 0
            setOnClickListener { findNavController().navigate(destinationId) }
        }
    }

    private fun parseLinks(raw: String?): List<Pair<String, String>> =
        raw.orEmpty().split(';').mapNotNull { item ->
            val parts = item.split('|', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
        }
}
