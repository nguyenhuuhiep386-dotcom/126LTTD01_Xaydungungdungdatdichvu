package vn.servio.partner.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import vn.servio.core.network.ApiResponse
import vn.servio.partner.data.model.AddDocumentRequest
import vn.servio.partner.data.model.AddSkillRequest
import vn.servio.partner.data.model.PartnerDocument
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.model.PartnerSkill
import vn.servio.partner.data.model.UpdatePartnerRequest
import vn.servio.partner.data.model.UploadedFile
import vn.servio.partner.data.model.VerificationStatusResult

// Paths are relative to BASE_URL + "api/v1/". Endpoint numbers (#n) refer to spec section 6.

/** Partner profile, KYC and skills (DT-1). */
interface PartnerApi {
    /** #17 */
    @GET("partners/me")
    suspend fun getMe(): ApiResponse<PartnerMe>

    /** #18 */
    @PATCH("partners/me")
    suspend fun updateMe(@Body body: UpdatePartnerRequest): ApiResponse<PartnerMe>

    /** #19 */
    @POST("partners/me/documents")
    suspend fun addDocument(@Body body: AddDocumentRequest): ApiResponse<PartnerDocument>

    /** #20 */
    @POST("partners/me/submit-verification")
    suspend fun submitVerification(): ApiResponse<VerificationStatusResult>

    /** #21 */
    @GET("partners/me/skills")
    suspend fun listSkills(): ApiResponse<List<PartnerSkill>>

    /** #22 */
    @POST("partners/me/skills")
    suspend fun addSkill(@Body body: AddSkillRequest): ApiResponse<PartnerSkill>

    /** #23 */
    @DELETE("partners/me/skills/{id}")
    suspend fun deleteSkill(@Path("id") id: String): Response<Unit>
}

/** POST /files (BE-1): one JPEG/PNG/WebP image, max 5 MB. */
interface FileApi {
    @Multipart
    @POST("files")
    suspend fun upload(@Part file: MultipartBody.Part, @Part("purpose") purpose: RequestBody): ApiResponse<UploadedFile>
}
