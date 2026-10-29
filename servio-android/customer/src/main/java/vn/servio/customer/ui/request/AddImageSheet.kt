package vn.servio.customer.ui.request

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import vn.servio.customer.R
import vn.servio.customer.databinding.SheetAddImageBinding

/**
 * CS-08 "Thêm ảnh": "Chụp ảnh" (hidden on devices without a camera) and "Chọn từ thư viện".
 * The choice goes back to the host as a fragment result [REQUEST_KEY] with [RESULT_SOURCE].
 */
class AddImageSheet : BottomSheetDialogFragment() {

    enum class Source { CAMERA, GALLERY }

    override fun getTheme() = R.style.ThemeOverlay_Servio_BottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = SheetAddImageBinding.inflate(inflater, container, false)
        binding.cameraRow.isVisible = requireArguments().getBoolean(ARG_SHOW_CAMERA)
        binding.cameraRow.setOnClickListener { choose(Source.CAMERA) }
        binding.galleryRow.setOnClickListener { choose(Source.GALLERY) }
        return binding.root
    }

    private fun choose(source: Source) {
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_SOURCE to source.name))
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "add_image"
        const val RESULT_SOURCE = "source"
        private const val ARG_SHOW_CAMERA = "showCamera"
        private const val TAG = "AddImageSheet"

        fun show(fragmentManager: FragmentManager, showCamera: Boolean) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            AddImageSheet().apply { arguments = bundleOf(ARG_SHOW_CAMERA to showCamera) }.show(fragmentManager, TAG)
        }
    }
}
