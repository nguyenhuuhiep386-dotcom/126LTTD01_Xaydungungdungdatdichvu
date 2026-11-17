package vn.servio.partner.ui.common

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.ImageView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.servio.partner.R
import vn.servio.partner.data.image.CameraCaptureFiles
import vn.servio.partner.data.image.ScaledImageDecoder

/**
 * "Chụp ảnh" / "Chọn từ thư viện" for a Fragment (KYC, avatar, certificate, order photos).
 * Create it as a property of the Fragment so the result launchers are registered before the view exists.
 * ponytail: the pending capture uri is lost if the process dies while the camera app is open; the user simply retakes.
 */
class PhotoPicker(
    private val fragment: Fragment,
    private val captureFiles: () -> CameraCaptureFiles,
    private val onPicked: (Uri) -> Unit,
) {
    private var pendingCapture: Uri? = null

    private val takePicture = fragment.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture ?: return@registerForActivityResult
        pendingCapture = null
        if (saved && captureFiles().hasContent(uri)) onPicked(uri) else captureFiles().delete(uri)
    }

    private val pickMedia = fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }

    /** Shows the choice; [cameraOnly] skips the gallery (KYC photos must be taken now). */
    fun show(cameraOnly: Boolean = false) {
        val hasCamera = fragment.requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        when {
            cameraOnly && hasCamera -> capture()
            !hasCamera -> pickFromGallery()
            else -> MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.photo_picker_title)
                .setItems(arrayOf(fragment.getString(R.string.photo_take), fragment.getString(R.string.photo_gallery))) { _, which ->
                    if (which == 0) capture() else pickFromGallery()
                }
                .show()
        }
    }

    private fun capture() {
        val uri = captureFiles().create()
        pendingCapture = uri
        runCatching { takePicture.launch(uri) }.onFailure {
            pendingCapture = null
            captureFiles().delete(uri)
            pickFromGallery()
        }
    }

    private fun pickFromGallery() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** Shows a local photo (content:// uri) in [target], decoded small off the main thread. */
fun Fragment.showLocalPhoto(target: ImageView, uri: Uri, maxEdge: Int = 480) {
    val resolver = requireContext().contentResolver
    viewLifecycleOwner.lifecycleScope.launch {
        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            runCatching { ScaledImageDecoder.decode(resolver, uri, maxEdge) }.getOrNull()
        }
        if (bitmap != null) target.setImageBitmap(bitmap)
    }
}
