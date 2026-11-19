package vn.servio.customer.ui.order

import vn.servio.customer.data.model.Tracking
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * What CS-20 (ON_THE_WAY card) and CS-21 show of #76 / PartnerLocationUpdated. Nothing is counted down or guessed:
 * a fix older than [STALE_AFTER_SECONDS] (checked by the phone every [CHECK_EVERY_MILLIS]) hides the ETA and the
 * distance until a newer fix comes.
 */
object TrackingUi {
    const val STALE_AFTER_SECONDS = 30L
    const val CHECK_EVERY_MILLIS = 5_000L

    private val VIETNAMESE = Locale("vi", "VN")

    sealed interface View {
        /** #76 has not answered (yet) or failed: "Chưa cập nhật". */
        data object Unknown : View

        /** The partner sent no position yet: "Chưa có vị trí của đối tác". */
        data object NoLocation : View

        /** Fresh fix: ETA and distance of the server, at [capturedAt]. */
        data class Live(val etaMinutes: Int, val distanceKm: Double, val capturedAt: String) : View

        /** The last fix is too old (or came without ETA/distance): only its time is shown. */
        data class Stale(val capturedAt: String) : View
    }

    fun of(tracking: Tracking?, now: Instant = Instant.now()): View {
        tracking ?: return View.Unknown
        val location = tracking.location ?: return View.NoLocation
        val captured = instant(location.capturedAt) ?: return View.Unknown
        val eta = tracking.etaMinutes
        val km = tracking.distanceKm
        return when {
            Duration.between(captured, now).seconds > STALE_AFTER_SECONDS -> View.Stale(location.capturedAt)
            eta == null || km == null -> View.Stale(location.capturedAt)
            else -> View.Live(eta, km, location.capturedAt)
        }
    }

    /** "3,2": one decimal, Vietnamese comma (the unit comes from strings.xml with a no-break space). */
    fun distance(km: Double): String = String.format(VIETNAMESE, "%.1f", km)

    /**
     * The newer of the two by fix time, so a late #76 answer never moves the marker back past an event that came
     * meanwhile. A fix without a readable time loses against one that has it.
     */
    fun newer(current: Tracking?, incoming: Tracking): Tracking {
        val old = current?.location?.capturedAt?.let(::instant) ?: return incoming
        val new = incoming.location?.capturedAt?.let(::instant) ?: return if (incoming.location == null) incoming else current
        return if (new.isBefore(old)) current else incoming
    }

    private fun instant(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
}
