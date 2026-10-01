package vn.servio.core.data.model

import kotlinx.serialization.Serializable

// Enum names match the server JSON (UPPER_SNAKE_CASE), so no @SerialName is needed.

@Serializable
enum class AppFlavor { CUSTOMER, PARTNER }

@Serializable
enum class OtpPurpose { REGISTER, LOGIN }

@Serializable
enum class UserRole { CUSTOMER, PARTNER }

@Serializable
enum class Gender { OTHER, MALE, FEMALE }

@Serializable
enum class PartnerVerificationStatus { NOT_SUBMITTED, PENDING, APPROVED, REJECTED }

// #1 POST /auth/otp/request
@Serializable
data class OtpRequest(val phoneNumber: String, val purpose: OtpPurpose, val appFlavor: AppFlavor)

@Serializable
data class OtpRequestResult(
    val otpId: String,
    val expiresInSeconds: Int,
    val resendAfterSeconds: Int,
    val maskedPhone: String,
)

// #2 POST /auth/otp/verify
@Serializable
data class OtpVerifyRequest(
    val otpId: String,
    val code: String,
    val deviceId: String,
    val fcmToken: String? = null,
    val appFlavor: AppFlavor,
)

@Serializable
data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val user: Me,
    val isNewUser: Boolean,
    val isNewRole: Boolean,
    val needsProfileCompletion: Boolean,
)

// #5 POST /auth/refresh
@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class RefreshResult(val accessToken: String, val refreshToken: String, val expiresIn: Int)

// #6 POST /auth/logout
@Serializable
data class LogoutRequest(val deviceId: String, val appFlavor: AppFlavor)
