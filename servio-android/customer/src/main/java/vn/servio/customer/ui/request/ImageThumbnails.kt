package vn.servio.customer.ui.request

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.servio.customer.data.image.ScaledImageDecoder

/**
 * CS-08 thumbnails decoded from the local uri (the project has no image-loading library).
 * Decoding runs on IO in [scope] (the view lifecycle); a recycled ImageView only gets the bitmap of its latest uri.
 */
class ImageThumbnails(
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val maxEdge: Int,
) {
    private val cache = object : LruCache<Uri, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }
    private val jobs = mutableMapOf<ImageView, Job>()

    /** Shows the thumbnail of [uri] in [view]; leaves it empty when the image cannot be read. */
    fun load(uri: Uri, view: ImageView) {
        jobs.remove(view)?.cancel()
        view.tag = uri
        val cached = cache.get(uri)
        view.setImageBitmap(cached)
        if (cached != null) return
        jobs[view] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { ScaledImageDecoder.decode(resolver, uri, maxEdge) }.getOrNull()
            } ?: return@launch
            cache.put(uri, bitmap)
            if (view.tag == uri) view.setImageBitmap(bitmap)
            jobs.remove(view)
        }
    }

    private companion object {
        const val CACHE_BYTES = 8 * 1024 * 1024
    }
}
