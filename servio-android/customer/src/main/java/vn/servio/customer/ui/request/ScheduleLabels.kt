package vn.servio.customer.ui.request

import android.content.Context
import vn.servio.customer.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** CS-09 labels (also for the CS-11 summary): "T5 30/10", "CN 01/11", "08:00 – 10:00". */
object ScheduleLabels {

    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

    /** Vietnamese weekdays count from Sunday: Monday is "T2" … Saturday "T7", Sunday "CN". */
    fun day(context: Context, date: LocalDate): String {
        val dayMonth = date.format(DAY_MONTH)
        return if (date.dayOfWeek == DayOfWeek.SUNDAY) {
            context.getString(R.string.create_request_day_sunday, dayMonth)
        } else {
            context.getString(R.string.create_request_day_weekday, date.dayOfWeek.value + 1, dayMonth)
        }
    }

    fun slot(context: Context, startHour: Int): String =
        context.getString(R.string.create_request_slot_label, startHour, startHour + ScheduleSlots.WINDOW_HOURS.toInt())
}
