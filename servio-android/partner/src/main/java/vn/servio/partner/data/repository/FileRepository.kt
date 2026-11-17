package vn.servio.partner.data.repository

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
import vn.servio.partner.R
import vn.servio.partner.data.image.ScaledImageDecoder
import vn.servio.partner.data.model.FilePurpose
import vn.servio.partner.data.model.UploadedFile
import vn.servio.partner.data.remote.FileApi
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Image upload through POST /files: avatar, KYC, certificates, order photos (same rules as the customer app). */
interface FileRepository {
    /** Shrinks the image (long edge ≤ 1600 px, JPEG ≤ 5 MB, no EXIF) and uploads it with [purpose]. */
    suspend fun uploadImage(uri: Uri, purpose: FilePurpose): ApiResult<UploadedFile>
}

@Singleton
class RemoteFileRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: FileApi,
) : FileRepository {

    override suspend fun uploadImage(uri: Uri, purpose: FilePurpose): ApiResult<UploadedFile> {
        val bytes = withContext(Dispatchers.IO) { runCatching { compress(uri) }.getOrNull() }
            ?: return ApiResult.Failure(IMAGE_UNREADABLE, context.getString(R.string.image_unreadable))
        if (bytes.size > MAX_BYTES) {
            return ApiResult.Failure(FILE_TOO_LARGE, ErrorMessages.forCode(FILE_TOO_LARGE))
        }
        val file = MultipartBody.Part.createFormData("file", "image.jpg", bytes.toRequestBody(JPEG))
        return apiCall { api.upload(file, purpose.name.toRequestBody(TEXT)) }
    }

    /** Re-encodes every image: the new JPEG has no EXIF, so no GPS position reaches the server. */
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
