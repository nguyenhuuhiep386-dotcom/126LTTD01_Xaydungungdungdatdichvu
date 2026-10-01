package vn.servio.core.data.repository

import vn.servio.core.data.local.TokenStore
import vn.servio.core.data.model.AuthResult
import vn.servio.core.data.model.LogoutRequest
import vn.servio.core.data.model.Me
import vn.servio.core.data.model.OtpPurpose
import vn.servio.core.data.model.OtpRequest
import vn.servio.core.data.model.OtpRequestResult
import vn.servio.core.data.model.OtpVerifyRequest
import vn.servio.core.data.model.UpdateMeRequest
import vn.servio.core.data.remote.AuthApi
import vn.servio.core.data.remote.UserApi
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.core.utils.AppConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Login, session and current user. Used by the shared auth screens and both apps. */
interface AuthRepository {
    val hasSession: Boolean
    suspend fun requestOtp(phoneNumber: String): ApiResult<OtpRequestResult>
    suspend fun verifyOtp(otpId: String, code: String): ApiResult<AuthResult>
    suspend fun getMe(): ApiResult<Me>
    suspend fun updateMe(request: UpdateMeRequest): ApiResult<Me>
    suspend fun logout()
}

@Singleton
class RemoteAuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
    private val appConfig: AppConfig,
) : AuthRepository {

    override val hasSession: Boolean get() = tokenStore.hasSession

    override suspend fun requestOtp(phoneNumber: String) = apiCall {
        authApi.requestOtp(OtpRequest(phoneNumber, OtpPurpose.LOGIN, appConfig.flavor))
    }

    override suspend fun verifyOtp(otpId: String, code: String): ApiResult<AuthResult> {
        val result = apiCall {
            authApi.verifyOtp(OtpVerifyRequest(otpId, code, tokenStore.deviceId, fcmToken = null, appFlavor = appConfig.flavor))
        }
        if (result is ApiResult.Success) {
            tokenStore.save(result.data.accessToken, result.data.refreshToken)
        }
        return result
    }

    override suspend fun getMe() = apiCall { userApi.getMe() }

    override suspend fun updateMe(request: UpdateMeRequest) = apiCall { userApi.updateMe(request) }

    /** Best effort: the local session is cleared even if the server call fails (e.g. offline). */
    override suspend fun logout() {
        apiCallNoContent { authApi.logout(LogoutRequest(tokenStore.deviceId, appConfig.flavor)) }
        tokenStore.clear()
    }
}
