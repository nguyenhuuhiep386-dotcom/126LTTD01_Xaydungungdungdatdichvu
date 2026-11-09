package vn.servio.customer.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import vn.servio.customer.data.image.ScaledImageDecoder
import vn.servio.customer.data.model.UploadedFile
import vn.servio.customer.data.remote.FileApi
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

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

    /**
     * Decodes upright at most [MAX_EDGE] px on the long edge, then lowers JPEG quality until the file fits [MAX_BYTES].
     * Every image is re-encoded, small ones too: the new JPEG has no EXIF, so no GPS position reaches the server.
     */
    private fun compress(uri: Uri): ByteArray {
        val bitmap = ScaledImageDecoder.decode(context.contentResolver, uri, MAX_EDGE)
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
