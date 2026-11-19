package vn.servio.core.data.repository

import vn.servio.core.BuildConfig
import vn.servio.core.data.model.ConsentChoice
import vn.servio.core.data.model.ConsentRecord
import vn.servio.core.data.model.LegalDocuments
import vn.servio.core.data.model.LegalPurpose
import vn.servio.core.data.remote.LegalConsentApi
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Terms and privacy consent (Luật 91/2025), asked by the shared consent screen of :core in both apps, and the account
 * deletion of the customer's CS-32. The partner app keeps its own KYC and location consents (its ConsentGate).
 */
interface LegalConsentRepository {
    /** GET /legal (public): the version to agree to and the two pages. */
    suspend fun legal(): ApiResult<LegalDocuments>

    /**
     * True when the user has not agreed to the current terms and privacy policy. A failed call answers false: the
     * app goes on and the server still refuses what needs the consent (422 CONSENT_REQUIRED).
     */
    suspend fun needsTermsConsent(): Boolean

    /** POST TERMS_AND_PRIVACY granted for [policyVersion] (the one the user just read). */
    suspend fun grantTerms(policyVersion: String): ApiResult<Unit>

    /** Reads the current version, then POST TERMS_AND_PRIVACY granted=false. */
    suspend fun withdrawTerms(): ApiResult<Unit>

    /** DELETE /users/me. The session is not cleared here: the caller does it after a success. */
    suspend fun deleteAccount(): ApiResult<Unit>
}

@Singleton
class RemoteLegalConsentRepository @Inject constructor(
    private val api: LegalConsentApi,
) : LegalConsentRepository {

    override suspend fun legal() = apiCall { api.legal() }

    override suspend fun needsTermsConsent(): Boolean = when (val result = apiCall { api.consents() }) {
        is ApiResult.Success -> LegalConsents.needsTermsConsent(result.data)
        is ApiResult.Failure -> false
    }

    override suspend fun grantTerms(policyVersion: String) = record(true, policyVersion)

    override suspend fun withdrawTerms(): ApiResult<Unit> = when (val legal = legal()) {
        is ApiResult.Success -> record(false, legal.data.policyVersion)
        is ApiResult.Failure -> legal
    }

    override suspend fun deleteAccount() = apiCallNoContent { api.deleteAccount() }

    private suspend fun record(granted: Boolean, policyVersion: String): ApiResult<Unit> =
        when (val result = apiCall { api.record(ConsentChoice(LegalPurpose.TERMS_AND_PRIVACY, granted, policyVersion)) }) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
}

/** Plain rules of the consent flow, kept apart so they can be unit tested. */
object LegalConsents {

    /** Asked again unless TERMS_AND_PRIVACY is granted on the current policy version. */
    fun needsTermsConsent(records: List<ConsentRecord>): Boolean =
        records.none { it.purpose == LegalPurpose.TERMS_AND_PRIVACY && it.granted && it.isCurrentVersion }

    /**
     * A page of GET /legal as a full address: [path] ("/chinh-sach-bao-mat") after [baseUrl] ("http://10.0.2.2:5080/")
     * with exactly one slash between. An absolute address is kept as it is; a blank path gives null.
     */
    fun pageUrl(path: String, baseUrl: String = BuildConfig.BASE_URL): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return baseUrl.trimEnd('/') + "/" + trimmed.trimStart('/')
    }
}
