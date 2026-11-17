package vn.servio.partner.data.model

import kotlinx.serialization.Serializable
import vn.servio.core.data.model.PartnerVerificationStatus

// Partner profile, KYC and skills (BE-1, spec 6 #17–#23). Enum names match the server JSON (UPPER_SNAKE_CASE).

@Serializable
enum class DocumentType { ID_FRONT, ID_BACK, SELFIE_WITH_ID, CERTIFICATE, CRIMINAL_RECORD }

/** Status of one KYC document or one skill. */
@Serializable
enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

/** #17 GET /partners/me */
@Serializable
data class PartnerMe(
    val id: String,
    val userId: String,
    val fullName: String,
    val avatarUrl: String? = null,
    val phoneNumber: String,
    val bio: String? = null,
    val yearsOfExperience: Int = 0,
    val verificationStatus: PartnerVerificationStatus,
    val verificationNote: String? = null,
    val isOnline: Boolean = false,
    val lastHeartbeatAt: String? = null,
    val serviceRadiusKm: Int = 10,
    val anchorLatitude: Double? = null,
    val anchorLongitude: Double? = null,
    val averageRating: Double? = null,
    val totalReviews: Int = 0,
    val completedOrders: Int = 0,
    val cancelledOrders: Int = 0,
    val documents: List<PartnerDocument> = emptyList(),
    val skills: List<PartnerSkill> = emptyList(),
    /** Vietnamese labels of what #20 still needs, e.g. "CCCD mặt sau". */
    val missingForVerification: List<String> = emptyList(),
) {
    /** The current document of a type: the server keeps one per type, a rejected one until it is replaced. */
    fun document(type: DocumentType): PartnerDocument? = documents.lastOrNull { it.documentType == type }

    val hasAnchor: Boolean get() = anchorLatitude != null && anchorLongitude != null
}

@Serializable
data class PartnerDocument(
    val id: String,
    val documentType: DocumentType,
    val fileUrl: String,
    val status: ApprovalStatus,
    val rejectReason: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class PartnerSkill(
    val id: String,
    val categoryId: String,
    val categoryName: String,
    val parentCategoryName: String? = null,
    val status: ApprovalStatus,
    val yearsOfExperience: Int = 0,
    val certificateUrl: String? = null,
)

/** #18 PATCH /partners/me — null fields are not changed. Radius: 3, 5, 10 or 20 km. */
@Serializable
data class UpdatePartnerRequest(
    val bio: String? = null,
    val yearsOfExperience: Int? = null,
    val serviceRadiusKm: Int? = null,
    val anchorLatitude: Double? = null,
    val anchorLongitude: Double? = null,
)

/** #19 POST /partners/me/documents. [fileUrl] comes from POST /files (purpose KYC or CERTIFICATE). */
@Serializable
data class AddDocumentRequest(val documentType: DocumentType, val fileUrl: String)

/** #22 POST /partners/me/skills. [serviceCategoryId] is a level-2 category. */
@Serializable
data class AddSkillRequest(
    val serviceCategoryId: String,
    val yearsOfExperience: Int,
    val certificateUrl: String? = null,
)

/** #20 POST /partners/me/submit-verification */
@Serializable
data class VerificationStatusResult(val verificationStatus: PartnerVerificationStatus)

/** Response of POST /files. [url] is what other requests take. */
@Serializable
data class UploadedFile(
    val fileId: String,
    val url: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** UploadedFiles.Purpose sent with POST /files. KYC, CERTIFICATE and DISPUTE_EVIDENCE are stored privately. */
enum class FilePurpose { AVATAR, KYC, CERTIFICATE, ORDER_IMAGE, CHAT_IMAGE, DISPUTE_EVIDENCE }
