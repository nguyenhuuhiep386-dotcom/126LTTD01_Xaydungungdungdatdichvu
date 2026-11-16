package vn.servio.partner.data.repository

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/** Consent purposes (Luật 91/2025): KYC photos, sharing the position, terms when submitting the profile. */
@Serializable
enum class ConsentPurpose { TERMS_AND_PRIVACY, KYC_IDENTITY, LOCATION_SHARING }

@Serializable
data class LegalInfo(val policyVersion: String, val privacyPolicyUrl: String = "", val termsUrl: String = "")

@Serializable
data class Consent(val purpose: ConsentPurpose, val granted: Boolean, val policyVersion: String, val isCurrentVersion: Boolean = true)

@Serializable
data class ConsentRequest(val purpose: ConsentPurpose, val granted: Boolean, val policyVersion: String)

interface ConsentApi {
    @GET("legal")
    suspend fun legal(): ApiResponse<LegalInfo>

    @GET("users/me/consents")
    suspend fun list(): ApiResponse<List<Consent>>

    @POST("users/me/consents")
    suspend fun record(@Body body: ConsentRequest): ApiResponse<List<Consent>>
}

/**
 * Consents the server may require before KYC upload/submit (KYC_IDENTITY, TERMS_AND_PRIVACY) and before going Online or
 * moving (LOCATION_SHARING): otherwise 422 CONSENT_REQUIRED. When the backend has no consent API yet (older build),
 * [missing] returns an empty list so the app behaves as before.
 */
interface ConsentRepository {
    suspend fun missing(purposes: List<ConsentPurpose>): List<ConsentPurpose>
    suspend fun grant(purposes: List<ConsentPurpose>): ApiResult<Unit>
    suspend fun legalInfo(): LegalInfo?
}

@Singleton
class RemoteConsentRepository @Inject constructor(
    private val api: ConsentApi,
) : ConsentRepository {

    override suspend fun missing(purposes: List<ConsentPurpose>): List<ConsentPurpose> {
        val current = apiCall { api.list() } as? ApiResult.Success ?: return emptyList()
        return purposes.filter { purpose ->
            current.data.none { it.purpose == purpose && it.granted && it.isCurrentVersion }
        }
    }

    override suspend fun grant(purposes: List<ConsentPurpose>): ApiResult<Unit> {
        val legal = when (val result = apiCall { api.legal() }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return result
        }
        for (purpose in purposes) {
            val result = apiCall { api.record(ConsentRequest(purpose, true, legal.policyVersion)) }
            if (result is ApiResult.Failure) return result
        }
        return ApiResult.Success(Unit)
    }

    override suspend fun legalInfo(): LegalInfo? = (apiCall { api.legal() } as? ApiResult.Success)?.data
}
