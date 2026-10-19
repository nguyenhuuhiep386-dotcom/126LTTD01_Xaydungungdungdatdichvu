package vn.servio.customer.ui.request

import android.net.Uri
import vn.servio.core.data.model.Category
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.model.ScheduleType
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Everything typed on CS-08 → CS-10, shown again on CS-11 and sent as #36. */
data class RequestDraft(
    val title: String = "",
    val description: String = "",
    val images: List<DraftImage> = emptyList(),
    val address: Address? = null,
    val scheduleType: ScheduleType = ScheduleType.NOW,
    val scheduledDate: LocalDate? = null,
    /** Start hour of the chosen 2-hour slot, one of [ScheduleSlots.START_HOURS]. */
    val scheduledHour: Int? = null,
    /** Both null = "Để người làm báo giá". */
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val requireExperience: Boolean = false,
    val requireCertificate: Boolean = false,
    val requireHighRating: Boolean = false,
    val searchRadiusKm: Int = DEFAULT_RADIUS_KM,
) {
    val hasBudget: Boolean get() = budgetMin != null && budgetMax != null

    companion object {
        const val TITLE_MIN = 5
        const val TITLE_MAX = 150
        const val DESCRIPTION_MIN = 20
        const val DESCRIPTION_MAX = 2000
        const val MAX_IMAGES = 6

        // CS-10 choices from Figma: "Có từ 3 năm kinh nghiệm", "Đánh giá từ 4,5 sao", radius chips.
        const val EXPERIENCE_YEARS = 3
        const val MIN_RATING = 4.5
        const val DEFAULT_RADIUS_KM = 10
        val RADIUS_OPTIONS_KM = listOf(5, 10, 20)
    }
}

/** A picked photo. It is uploaded right after picking; [url] is set once POST /files succeeds. */
data class DraftImage(
    val localId: Long,
    val uri: Uri,
    val status: Status,
    val url: String? = null,
    val errorMessage: String? = null,
) {
    enum class Status { UPLOADING, FAILED, UPLOADED }
}

/** The chosen level-2 service and its level-1 group ("Sửa máy lạnh" · "Điện lạnh"). */
data class CategoryChoice(val service: Category, val group: Category)

/**
 * CS-09 time slots: 2-hour windows starting at [START_HOURS], Vietnam time. A slot can be booked when it starts
 * at least 30 minutes from now and at most 30 days from now — the same window the server checks for #36.
 */
object ScheduleSlots {
    val ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    val START_HOURS = listOf(8, 10, 14, 16, 18)
    const val WINDOW_HOURS = 2L
    const val MAX_DAYS_AHEAD = 30L
    private val MIN_LEAD: Duration = Duration.ofMinutes(30)

    fun startOf(date: LocalDate, hour: Int): ZonedDateTime = date.atTime(hour, 0).atZone(ZONE)

    fun isAvailable(date: LocalDate, hour: Int, now: ZonedDateTime): Boolean {
        val start = startOf(date, hour)
        return !start.isBefore(now.plus(MIN_LEAD)) && !start.isAfter(now.plusDays(MAX_DAYS_AHEAD))
    }

    /** Today through today + 30 days; a day is only selectable when at least one of its slots is. */
    fun days(now: ZonedDateTime): List<LocalDate> {
        val today = now.withZoneSameInstant(ZONE).toLocalDate()
        return (0..MAX_DAYS_AHEAD).map { today.plusDays(it) }
    }

    fun hasAvailableSlot(date: LocalDate, now: ZonedDateTime): Boolean = START_HOURS.any { isAvailable(date, it, now) }
}
