package vn.servio.partner.ui.common

import android.net.Uri
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.partner.data.model.FilePurpose
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.databinding.ItemPhotoTileBinding

/**
 * Photos picked on an order screen (before/after photos, extra-charge evidence), each uploaded at once
 * through POST /files. Owned by a ViewModel: create it with viewModelScope.
 */
class PhotoUploads(
    private val scope: CoroutineScope,
    private val fileRepository: FileRepository,
    private val purpose: FilePurpose = FilePurpose.ORDER_IMAGE,
    val max: Int = 10,
) {
    data class Photo(val localUri: Uri, val url: String? = null, val uploading: Boolean = true)

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)

    /** Last upload error, cleared by [consumeError]. */
    val errors: StateFlow<String?> = _errors.asStateFlow()

    val urls: List<String> get() = _photos.value.mapNotNull { it.url }
    val isUploading: Boolean get() = _photos.value.any { it.uploading }

    fun add(uri: Uri) {
        if (_photos.value.size >= max) return
        _photos.update { it + Photo(uri) }
        scope.launch {
            when (val result = fileRepository.uploadImage(uri, purpose)) {
                is ApiResult.Success -> _photos.update { list ->
                    list.map { if (it.localUri == uri) it.copy(url = result.data.url, uploading = false) else it }
                }
                is ApiResult.Failure -> {
                    _photos.update { list -> list.filterNot { it.localUri == uri } }
                    _errors.value = result.message
                }
            }
        }
    }

    fun remove(uri: Uri) {
        _photos.update { list -> list.filterNot { it.localUri == uri } }
    }

    fun consumeError() {
        _errors.value = null
    }
}

/** Draws [photos] as 88 dp tiles in [strip], followed by an "add" tile while under [max]. */
fun Fragment.bindPhotoStrip(
    strip: LinearLayout,
    photos: List<PhotoUploads.Photo>,
    max: Int,
    onAdd: () -> Unit,
    onRemove: (Uri) -> Unit,
) {
    strip.removeAllViews()
    val inflater = LayoutInflater.from(strip.context)
    photos.forEach { photo ->
        val tile = ItemPhotoTileBinding.inflate(inflater, strip, false)
        showLocalPhoto(tile.image, photo.localUri, maxEdge = 240)
        tile.progress.isVisible = photo.uploading
        tile.addIcon.isVisible = false
        tile.removeButton.isVisible = !photo.uploading
        tile.removeButton.setOnClickListener { onRemove(photo.localUri) }
        strip.addView(tile.root)
    }
    if (photos.size < max) {
        val add = ItemPhotoTileBinding.inflate(inflater, strip, false)
        add.progress.isVisible = false
        add.removeButton.isVisible = false
        add.addIcon.isVisible = true
        add.root.setOnClickListener { onAdd() }
        strip.addView(add.root)
    }
}
