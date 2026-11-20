package vn.servio.customer.ui.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.ui.order.OrderTexts
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Pure rules of CS-31 and of the CS-06 bell: day groups, the screen a notification opens, the badge text. */
object NotificationTexts {

    /** Days are cut at midnight in Vietnam, whatever the phone's zone. */
    val VN_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
    private val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    // ---- Day groups -------------------------------------------------------------------------------------------------

    /** Label of a day group; the texts come from strings.xml ([Labels]). */
    sealed interface Day {
        data object Today : Day
        data object Yesterday : Day

        /** "T5 19/11", or "T5 19/11/2025" in another year than today. */
        data class Date(val text: String) : Day
    }

    /** One line of the CS-31 list: a day header, then its notifications (newest first, as the server sends them). */
    sealed interface Line {
        data class Header(val day: Day) : Line
        data class Row(val notification: AppNotification, val time: String) : Line
    }

    private fun at(iso: String): ZonedDateTime? =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(VN_ZONE) }.getOrNull()

    fun day(date: LocalDate, today: LocalDate, weekdays: List<String>): Day = when (date) {
        today -> Day.Today
        today.minusDays(1) -> Day.Yesterday
        else -> {
            val format = if (date.year == today.year) DAY else DAY_YEAR
            Day.Date("${weekdays[date.dayOfWeek.value - 1]}${OrderTexts.NBSP}${format.format(date)}")
        }
    }

    /**
     * The list with a header before each new VN day. The order of [items] is kept (the server sorts newest first); a
     * row whose time cannot be read goes under the header of the row before it (or a header of today when first) and
     * shows no time.
     */
    fun lines(items: List<AppNotification>, today: LocalDate, weekdays: List<String>): List<Line> {
        val out = ArrayList<Line>(items.size + 4)
        var lastDay: Day? = null
        for (item in items) {
            val at = at(item.createdAt)
            val day = at?.let { day(it.toLocalDate(), today, weekdays) } ?: lastDay ?: Day.Today
            if (day != lastDay) {
                out += Line.Header(day)
                lastDay = day
            }
            out += Line.Row(item, at?.let { TIME.format(it) }.orEmpty())
        }
        return out
    }

    fun today(): LocalDate = LocalDate.now(VN_ZONE)

    // ---- Where a tap goes -------------------------------------------------------------------------------------------

    sealed interface Target {
        /** CS-20 (never CS-23 directly: CS-20 shows what the order needs). */
        data class Order(val orderId: String) : Target

        /** CS-15. */
        data class Request(val requestId: String) : Target

        /** CS-30. */
        data object Wallet : Target

        /** Only marked read. */
        data object None : Target
    }

    /**
     * From [AppNotification.deepLink]: "orders/{id}", "service-requests/{id}", "wallet". When the link is missing or not
     * one of those, the ids of [AppNotification.data] are used: orderId, serviceRequestId, transactionId (wallet).
     */
    fun target(notification: AppNotification): Target =
        fromDeepLink(notification.deepLink) ?: fromData(notification.data) ?: Target.None

    fun fromDeepLink(deepLink: String?): Target? {
        val parts = deepLink?.trim()?.trim('/')?.split('/')?.filter { it.isNotBlank() } ?: return null
        return when {
            parts.size == 1 && parts[0] == "wallet" -> Target.Wallet
            parts.size == 2 && parts[0] == "orders" -> Target.Order(parts[1])
            parts.size == 2 && parts[0] == "service-requests" -> Target.Request(parts[1])
            else -> null
        }
    }

    fun fromData(data: JsonObject?): Target? {
        if (data == null) return null
        fun text(key: String): String? = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        text("orderId")?.let { return Target.Order(it) }
        text("serviceRequestId")?.let { return Target.Request(it) }
        if (text("transactionId") != null) return Target.Wallet
        return null
    }

    // ---- Icon -------------------------------------------------------------------------------------------------------

    enum class Icon { QUOTE, ORDER, REVIEW, PAYMENT, OTHER }

    fun icon(type: String): Icon = when (type) {
        "NEW_QUOTE" -> Icon.QUOTE
        "ORDER_STATUS_CHANGED" -> Icon.ORDER
        "REVIEW" -> Icon.REVIEW
        "PAYMENT" -> Icon.PAYMENT
        else -> Icon.OTHER
    }

    // ---- Badge ------------------------------------------------------------------------------------------------------

    /** Text of the CS-06 bell badge: null (hidden) for 0, the number up to 9, then "9+". */
    fun badge(unread: Int?): String? = when {
        unread == null || unread <= 0 -> null
        unread > MAX_BADGE -> "$MAX_BADGE+"
        else -> unread.toString()
    }

    private const val MAX_BADGE = 9
}
