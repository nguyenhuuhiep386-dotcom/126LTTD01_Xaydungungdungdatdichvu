package vn.servio.customer.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import vn.servio.core.network.ApiResult
import vn.servio.core.network.ErrorMessages
import vn.servio.core.network.apiCall
import vn.servio.customer.R
import vn.servio.customer.data.model.UploadedFile
import vn.servio.customer.data.remote.FileApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Image upload through POST /files. Used by CS-08 (purpose POST_IMAGE). */
interface FileRepository {
    /** Shrinks the picked image (long edge ≤ 1600 px, JPEG ≤ 5 MB, F-REQ-03) and uploads it. */
    suspend fun uploadPostImage(uri: Uri): ApiResult<UploadedFile>
}

@Singleton
class RemoteFileRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: FileApi,
) : FileRepository {

    override suspend fun uploadPostImage(uri: Uri): ApiResult<UploadedFile> {
        val bytes = withContext(Dispatchers.IO) { runCatching { compress(uri) }.getOrNull() }
            ?: return ApiResult.Failure(IMAGE_UNREADABLE, context.getString(R.string.create_request_image_unreadable))
        if (bytes.size > MAX_BYTES) {
            return ApiResult.Failure(FILE_TOO_LARGE, ErrorMessages.forCode(FILE_TOO_LARGE))
        }
        val file = MultipartBody.Part.createFormData("file", "image.jpg", bytes.toRequestBody(JPEG))
        return apiCall { api.upload(file, PURPOSE_POST_IMAGE.toRequestBody(TEXT)) }
    }

    /** Decodes at most [MAX_EDGE] px on the long edge, then lowers JPEG quality until the file fits [MAX_BYTES]. */
    private fun compress(uri: Uri): ByteArray {
        val bitmap = decode(uri)
        try {
            var quality = START_QUALITY
            while (true) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_BYTES || quality <= MIN_QUALITY) return out.toByteArray()
                quality -= QUALITY_STEP
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the EXIF rotation of camera photos.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (width, height) = scaledSize(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // API 26–27: no EXIF rotation; sample down on decode, then scale to the exact size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot read image bounds")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val sampled = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Cannot decode image")
        val (width, height) = scaledSize(sampled.width, sampled.height)
        if (width == sampled.width && height == sampled.height) return sampled
        return Bitmap.createScaledBitmap(sampled, width, height, true).also { sampled.recycle() }
    }

    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= MAX_EDGE) return width to height
        val ratio = MAX_EDGE.toFloat() / longEdge
        return (width * ratio).roundToInt() to (height * ratio).roundToInt()
    }

    private companion object {
        const val PURPOSE_POST_IMAGE = "POST_IMAGE"
        const val IMAGE_UNREADABLE = "IMAGE_UNREADABLE"
        const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
        const val MAX_EDGE = 1600
        const val MAX_BYTES = 5 * 1024 * 1024
        const val START_QUALITY = 85
        const val MIN_QUALITY = 50
        const val QUALITY_STEP = 10
        val JPEG = "image/jpeg".toMediaType()
        val TEXT = "text/plain".toMediaType()
    }
}
