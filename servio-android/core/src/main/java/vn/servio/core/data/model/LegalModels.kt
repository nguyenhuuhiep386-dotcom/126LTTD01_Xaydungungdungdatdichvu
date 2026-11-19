package vn.servio.core.data.model

import kotlinx.serialization.Serializable

/*
 * Consent and personal-data rights (Luật 91/2025). Names differ from the partner app's own consent classes
 * (vn.servio.partner.data.repository) so both can be imported side by side without confusion.
 */

/** GET /legal: the policy version a consent must refer to, and the two public pages (paths relative to BASE_URL). */
@Serializable
data class LegalDocuments(
    val policyVersion: String,
    val privacyPolicyUrl: String = "",
    val termsUrl: String = "",
)

/**
 * One purpose of GET/POST /users/me/consents: the newest choice. [isCurrentVersion] false means the policy changed
 * since, so it has to be asked again. [purpose] is kept as text: a purpose added later by the server is ignored.
 */
@Serializable
data class ConsentRecord(
    val purpose: String,
    val granted: Boolean,
    val policyVersion: String,
    val isCurrentVersion: Boolean = false,
)

/** Body of POST /users/me/consents: granted=false withdraws. */
@Serializable
data class ConsentChoice(val purpose: String, val granted: Boolean, val policyVersion: String)

/** Purposes of the server. The shared flow of :core asks only [TERMS_AND_PRIVACY]. */
object LegalPurpose {
    const val TERMS_AND_PRIVACY = "TERMS_AND_PRIVACY"
}
