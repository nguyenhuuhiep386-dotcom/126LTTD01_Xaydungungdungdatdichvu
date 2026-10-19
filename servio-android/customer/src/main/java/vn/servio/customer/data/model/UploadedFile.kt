package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/** Response of POST /files. [url] is what other requests take (e.g. images[].url of #36). */
@Serializable
data class UploadedFile(
    val fileId: String,
    val url: String,
    val mimeType: String,
    val sizeBytes: Long,
)
