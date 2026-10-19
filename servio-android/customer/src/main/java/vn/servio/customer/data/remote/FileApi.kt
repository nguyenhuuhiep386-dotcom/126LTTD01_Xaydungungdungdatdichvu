package vn.servio.customer.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.UploadedFile

// Paths are relative to BASE_URL + "api/v1/".

/** POST /files (BE-1): one JPEG/PNG/WebP image, max 5 MB. */
interface FileApi {
    @Multipart
    @POST("files")
    suspend fun upload(@Part file: MultipartBody.Part, @Part("purpose") purpose: RequestBody): ApiResponse<UploadedFile>
}
