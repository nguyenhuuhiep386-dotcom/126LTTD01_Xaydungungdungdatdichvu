package vn.servio.core.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.data.model.AuthResult
import vn.servio.core.data.model.Category
import vn.servio.core.data.model.ConsentChoice
import vn.servio.core.data.model.ConsentRecord
import vn.servio.core.data.model.LegalDocuments
import vn.servio.core.data.model.LogoutRequest
import vn.servio.core.data.model.Me
import vn.servio.core.data.model.OtpRequest
import vn.servio.core.data.model.OtpRequestResult
import vn.servio.core.data.model.OtpVerifyRequest
import vn.servio.core.data.model.UpdateMeRequest
import vn.servio.core.network.ApiResponse

// Retrofit interfaces shared by both apps. Paths are relative to BASE_URL + "api/v1/".
// Endpoint numbers (#n) refer to spec section 6. App-specific APIs live in each app's data/remote package.

interface AuthApi {
    @POST("auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequest): ApiResponse<OtpRequestResult>

    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyRequest): ApiResponse<AuthResult>

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>
}

interface UserApi {
    @GET("users/me")
    suspend fun getMe(): ApiResponse<Me>

    @PATCH("users/me")
    suspend fun updateMe(@Body body: UpdateMeRequest): ApiResponse<Me>
}

/** Consent and account deletion (Luật 91/2025), for both apps. */
interface LegalConsentApi {
    /** Public: the current policy version and the two pages. */
    @GET("legal")
    suspend fun legal(): ApiResponse<LegalDocuments>

    @GET("users/me/consents")
    suspend fun consents(): ApiResponse<List<ConsentRecord>>

    @POST("users/me/consents")
    suspend fun record(@Body body: ConsentChoice): ApiResponse<List<ConsentRecord>>

    /** 204, or 409 ACCOUNT_DELETION_BLOCKED with the reason (an open order, request, quote or debt). */
    @DELETE("users/me")
    suspend fun deleteAccount(): Response<Unit>
}

interface CategoryApi {
    @GET("service-categories")
    suspend fun getTree(@Query("parentId") parentId: String? = null): ApiResponse<List<Category>>

    @GET("service-categories/{id}")
    suspend fun getById(@Path("id") id: String): ApiResponse<Category>
}
