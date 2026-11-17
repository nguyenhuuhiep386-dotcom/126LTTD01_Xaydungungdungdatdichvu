package vn.servio.core.ui.chat

import android.content.Context
import vn.servio.core.R
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Times on the chat screens: "10:14" today, "Hôm qua", otherwise "17/10". */
internal object ChatTexts {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val date = DateTimeFormatter.ofPattern("dd/MM")

    fun messageTime(iso: String): String =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(time) }.getOrDefault("")

    fun listTime(context: Context, iso: String?): String {
        val value = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() }
            ?: return ""
        val today = LocalDate.now()
        return when (value.toLocalDate()) {
            today -> value.format(time)
            today.minusDays(1) -> context.getString(R.string.chat_yesterday)
            else -> value.format(date)
        }
    }

    /** "MA" from "Minh Anh". */
    fun initials(name: String): String =
        name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
}
