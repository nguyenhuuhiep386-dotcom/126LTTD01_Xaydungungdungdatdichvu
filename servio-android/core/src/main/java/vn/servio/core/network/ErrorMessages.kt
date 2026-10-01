package vn.servio.core.network

/**
 * Vietnamese messages for server error codes (mirror of servio-backend Common/ErrorCodes.cs).
 * Codes not listed here use the message sent by the server.
 */
object ErrorMessages {
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val UNKNOWN = "UNKNOWN"

    private val messages = mapOf(
        NETWORK_ERROR to "Không có kết nối mạng. Vui lòng kiểm tra Wi-Fi/4G rồi thử lại.",
        UNKNOWN to "Có lỗi xảy ra, vui lòng thử lại.",
        "UNAUTHENTICATED" to "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.",
        "FORBIDDEN" to "Bạn không có quyền thực hiện thao tác này.",
        "RATE_LIMITED" to "Bạn thao tác quá nhanh, vui lòng thử lại sau.",
        "OTP_INVALID" to "Mã OTP không đúng.",
        "OTP_EXPIRED" to "Mã OTP đã hết hạn, vui lòng gửi lại.",
        "OTP_RATE_LIMITED" to "Bạn đã yêu cầu OTP quá nhiều lần, vui lòng thử lại sau.",
        "ACCOUNT_LOCKED" to "Tài khoản đã bị khoá, vui lòng liên hệ hỗ trợ.",
        "PARTNER_NOT_VERIFIED" to "Hồ sơ đối tác chưa được duyệt.",
        "PARTNER_OFFLINE" to "Bạn cần bật trạng thái Online để báo giá.",
        // BE-2. POSTING_RESTRICTED is not mapped: the server message says until when.
        "CONTACT_INFO_NOT_ALLOWED" to "Không ghi số điện thoại hoặc đường link. Hai bên trao đổi qua chat trong ứng dụng.",
        "REQUEST_NOT_OPEN" to "Bài đăng không còn nhận báo giá.",
        "RESOURCE_VERSION_CONFLICT" to "Bài đăng đã thay đổi, vui lòng tải lại.",
        "PARTNER_NOT_ELIGIBLE" to "Bạn không đủ điều kiện báo giá bài này.",
        // PROFILE_INCOMPLETE is not mapped: the server message lists exactly what is missing.
        "VERIFICATION_LOCKED" to "Hồ sơ đang chờ duyệt hoặc đã được duyệt, không thể thay đổi giấy tờ.",
        "SKILL_ALREADY_EXISTS" to "Bạn đã đăng ký kỹ năng này.",
        "SKILL_LIMIT_REACHED" to "Bạn chỉ được đăng ký tối đa 5 kỹ năng.",
        "CERTIFICATE_REQUIRED" to "Dịch vụ này cần ảnh chứng chỉ nghề.",
        "ADDRESS_LIMIT_REACHED" to "Sổ địa chỉ tối đa 10 địa chỉ.",
        "FILE_NOT_READY" to "Ảnh không hợp lệ, vui lòng tải lên lại.",
        "FILE_TOO_LARGE" to "Ảnh tối đa 5 MB.",
        "UNSUPPORTED_FILE_TYPE" to "Chỉ nhận ảnh JPEG, PNG hoặc WebP.",
        "QUOTE_LIMIT_EXCEEDED" to "Bạn đã đạt số báo giá đang chờ tối đa.",
        "QUOTE_ALREADY_EXISTS" to "Bạn đã báo giá cho bài này.",
        "QUOTE_ALREADY_ACCEPTED" to "Yêu cầu đã chọn đối tác khác.",
        "REQUEST_EXPIRED" to "Bài đăng đã hết hạn.",
        "INVALID_STATUS_TRANSITION" to "Trạng thái đơn đã thay đổi, vui lòng tải lại.",
        "TOO_FAR_FROM_ADDRESS" to "Bạn đang cách địa chỉ quá 200 m.",
        "MOCK_LOCATION_DETECTED" to "Phát hiện vị trí giả, không thể check-in.",
        "ORDER_NOT_CANCELLABLE" to "Không thể huỷ đơn ở trạng thái này.",
        "REVIEW_ALREADY_SUBMITTED" to "Bạn đã đánh giá đơn này.",
        "OUTSTANDING_DEBT" to "Bạn còn khoản phí chưa thanh toán.",
    )

    fun forCode(code: String, fallback: String? = null): String =
        messages[code] ?: fallback ?: messages.getValue(UNKNOWN)
}
