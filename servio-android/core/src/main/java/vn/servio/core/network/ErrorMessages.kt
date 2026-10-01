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
