package vn.servio.customer.ui.request

/**
 * Client copy of the server's ContentGuard (BE-2): titles and descriptions must not carry a phone number or a link,
 * so the deal stays in the app chat (F-REQ-02). Prices like "1.500.000" are not phone numbers.
 * Keep the two patterns in sync with servio-backend Services/Requests/ContentGuard.cs.
 */
object ContactInfoGuard {

    // 0 or +84/84 followed by 9–10 more digits, optionally separated by spaces, dots or dashes.
    private val phoneNumber = Regex("""(?<!\d)(?:\+?84|0)(?:[\s.\-]?\d){8,10}(?!\d)""")

    private val link = Regex(
        """https?://|www\.|\b[a-z0-9\-]+\.(?:com|vn|net|org|info|io|me|xyz|link|ly|co|app|site|online)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun containsContactInfo(text: String): Boolean = phoneNumber.containsMatchIn(text) || link.containsMatchIn(text)
}
