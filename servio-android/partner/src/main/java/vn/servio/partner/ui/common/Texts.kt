package vn.servio.partner.ui.common

import android.content.Context
import vn.servio.partner.R
import vn.servio.partner.data.model.ScheduleType
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date, time and distance texts shared by partner screens (Vietnamese format, device time zone). */
object Texts {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val vi = Locale.forLanguageTag("vi-VN")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dayMonth = DateTimeFormatter.ofPattern("dd/MM")
    private val full = DateTimeFormatter.ofPattern("HH:mm, dd/MM/yyyy")

    /** Parses an ISO-8601 instant with offset from the API; null when missing or malformed. */
    fun parse(iso: String?): ZonedDateTime? =
        iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }

    /** "T7 17/10" (or "CN 18/10"). */
    fun shortDay(value: ZonedDateTime): String {
        val day = value.dayOfWeek.value // 1 = Monday
        val name = if (day == 7) "CN" else "T${day + 1}"
        return "$name ${value.format(dayMonth)}"
    }

    fun time(value: ZonedDateTime): String = value.format(timeFormat)

    fun dateTime(iso: String?): String = parse(iso)?.format(full).orEmpty()

    /** "Hôm nay", "Ngày mai" or "T7 17/10". */
    fun dayLabel(context: Context, value: ZonedDateTime): String {
        val today = LocalDate.now(zone)
        return when (value.toLocalDate()) {
            today -> context.getString(R.string.day_today)
            today.plusDays(1) -> context.getString(R.string.day_tomorrow)
            else -> shortDay(value)
        }
    }

    /** "T7 17/10 · 14:00–16:00", or "Càng sớm càng tốt" for NOW posts. */
    fun schedule(context: Context, type: ScheduleType, startIso: String?, endIso: String?): String {
        val start = parse(startIso)
        if (type == ScheduleType.NOW || start == null) return context.getString(R.string.schedule_now)
        val end = parse(endIso)
        val range = if (end != null) "${time(start)}–${time(end)}" else time(start)
        return "${dayLabel(context, start)} · $range"
    }

    /** "Vừa xong", "3 phút trước", "2 giờ trước", then the date. */
    fun ago(context: Context, iso: String?): String {
        val value = parse(iso) ?: return ""
        val minutes = Duration.between(value, ZonedDateTime.now(zone)).toMinutes()
        return when {
            minutes < 1 -> context.getString(R.string.ago_now)
            minutes < 60 -> context.getString(R.string.ago_minutes, minutes)
            minutes < 24 * 60 -> context.getString(R.string.ago_hours, minutes / 60)
            else -> shortDay(value)
        }
    }

    /** "1,2 km" with a Vietnamese decimal comma. */
    fun distance(km: Double): String = String.format(vi, "%.1f km", km)

    /** ISO-8601 with offset, as the API expects (e.g. availableFrom). */
    fun iso(value: ZonedDateTime): String = value.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
