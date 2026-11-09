package vn.servio.customer.ui.request

/**
 * Client copy of the server's ContentGuard (BE-2): titles and descriptions must not carry a phone number or a link,
 * so the deal stays in the app chat (F-REQ-02). Prices like "1.500.000" are not phone numbers.
 * Keep the two patterns in sync with servio-backend Services/Requests/ContentGuard.cs.
 *
 * The server patterns use `\d`, `\s` and `\b`, which .NET reads as Unicode classes (full-width digits, NBSP and
 * Vietnamese letters count). Java/Android regex engines read them differently, so they are spelled out here with
 * the .NET meaning: same rule on both sides whatever the engine.
 */
object ContactInfoGuard {

    /** .NET `\d`. */
    private const val DIGIT = """\p{Nd}"""

    /** .NET `\s`: [\f\n\r\t\v\x85\p{Z}]. */
    private const val SPACE = """[\f\n\r\t\x0B\x85\p{Z}]"""

    /** .NET `\w` (letters, marks, digits, connectors, ZWNJ/ZWJ), used to rebuild `\b`. */
    private const val WORD = """[\p{L}\p{Mn}\p{Nd}\p{Pc}‌‍]"""

    /** .NET `\b`: a word character on exactly one side. */
    private const val BOUNDARY = """(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"""

    // 0 or +84/84 followed by 9–10 more digits, optionally separated by spaces, dots or dashes.
    // Server: (?<!\d)(?:\+?84|0)(?:[\s.\-]?\d){8,10}(?!\d)
    private val phoneNumber = Regex("""(?<!$DIGIT)(?:\+?84|0)(?:(?:$SPACE|[.\-])?$DIGIT){8,10}(?!$DIGIT)""")

    // Server: https?://|www\.|\b[a-z0-9\-]+\.(?:com|vn|...|online)\b (IgnoreCase)
    private val link = Regex(
        """https?://|www\.|$BOUNDARY[a-z0-9\-]+\.(?:com|vn|net|org|info|io|me|xyz|link|ly|co|app|site|online)$BOUNDARY""",
        RegexOption.IGNORE_CASE,
    )

    fun containsContactInfo(text: String): Boolean = phoneNumber.containsMatchIn(text) || link.containsMatchIn(text)
}
