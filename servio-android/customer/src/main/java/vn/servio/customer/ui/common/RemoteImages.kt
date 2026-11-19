package vn.servio.customer.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.servio.core.BuildConfig

/**
 * Images from the server (request photos, avatars) for any customer screen; the remote counterpart of
 * ImageThumbnails, as the project has no image-loading library.
 *
 * - The server sends relative urls ("/uploads/2026/11/….jpg"); they are resolved against BASE_URL.
 * - [apiClient] (the app's client, which adds the access token) is used only for the API host. Any other host gets
 *   a plain client, so the token never leaves our server.
 * - Bitmaps are scaled down to [maxEdge] and kept in an 8 MB memory cache shared by all screens.
 * - Loading into a view again (a recycled row) cancels its previous download. Downloads run in [scope]
 *   (the view lifecycle).
 * - A failed download leaves the view empty, so what is drawn behind it (the "Ảnh N" tile, the initial) shows.
 */
class RemoteImages(
    private val apiClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val maxEdge: Int,
) {
    private val jobs = mutableMapOf<ImageView, Job>()

    fun load(url: String?, view: ImageView) {
        jobs.remove(view)?.cancel()
        val absolute = url?.takeIf { it.isNotBlank() }?.let { absolute(it) }
        val cached = absolute?.let(cache::get)
        view.setImageBitmap(cached)
        if (absolute == null || cached != null) return
        jobs[view] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { download(absolute) }.getOrNull() }
            // Cancelled meanwhile = the view now belongs to another url: withContext has thrown, nothing is set.
            jobs.remove(view)
            if (bitmap != null) {
                cache.put(absolute, bitmap)
                view.setImageBitmap(bitmap)
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val client = if (isApiHost(url)) apiClient else plainClient
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes() ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        /** Longest edge kept for request photos (72 dp tiles) and avatars (48 dp). */
        const val IMAGE_EDGE_DP = 96

        private const val CACHE_BYTES = 8 * 1024 * 1024
        private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        /** No interceptors: no token, no app headers. */
        private val plainClient by lazy { OkHttpClient() }

        /** "/uploads/a.jpg" → "<base>/uploads/a.jpg"; an absolute http(s) url is kept. */
        fun absolute(url: String, baseUrl: String = BuildConfig.BASE_URL): String =
            if (url.startsWith("http://") || url.startsWith("https://")) url
            else baseUrl.trimEnd('/') + "/" + url.trimStart('/')

        /** True when [url] is on the same scheme, host and port as the API. */
        fun isApiHost(url: String, baseUrl: String = BuildConfig.BASE_URL): Boolean {
            val target = url.toHttpUrlOrNull() ?: return false
            val base = baseUrl.toHttpUrlOrNull() ?: return false
            return target.scheme == base.scheme && target.host == base.host && target.port == base.port
        }
    }
}
