package vn.servio.customer.ui.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.Tracking
import vn.servio.customer.data.model.TrackingLocation
import java.time.Instant

class TrackingUiTest {

    private val captured = "2026-11-18T06:52:10Z"
    private val at = Instant.parse(captured)

    private fun tracking(capturedAt: String? = captured, eta: Int? = 8, km: Double? = 3.2) = Tracking(
        orderId = "o1",
        assignmentId = "a1",
        assignmentStatus = OrderStatus.ON_THE_WAY,
        location = capturedAt?.let { TrackingLocation(10.85, 106.77, 10, it) },
        distanceKm = km,
        etaMinutes = eta,
    )

    @Test
    fun `a fix up to 30 s old shows the server's ETA and distance`() {
        assertEquals(TrackingUi.View.Live(8, 3.2, captured), TrackingUi.of(tracking(), at))
        assertEquals(TrackingUi.View.Live(8, 3.2, captured), TrackingUi.of(tracking(), at.plusSeconds(30)))
    }

    @Test
    fun `older than 30 s by the phone's clock is stale, a newer fix is live again`() {
        assertEquals(TrackingUi.View.Stale(captured), TrackingUi.of(tracking(), at.plusSeconds(31)))
        val newer = "2026-11-18T06:52:45Z"
        assertEquals(TrackingUi.View.Live(8, 3.2, newer), TrackingUi.of(tracking(capturedAt = newer), at.plusSeconds(40)))
    }

    @Test
    fun `nothing is guessed when data is missing`() {
        assertEquals(TrackingUi.View.Unknown, TrackingUi.of(null, at))
        assertEquals(TrackingUi.View.NoLocation, TrackingUi.of(tracking(capturedAt = null), at))
        assertEquals(TrackingUi.View.Stale(captured), TrackingUi.of(tracking(eta = null), at))
        assertEquals(TrackingUi.View.Stale(captured), TrackingUi.of(tracking(km = null), at))
        assertEquals(TrackingUi.View.Unknown, TrackingUi.of(tracking(capturedAt = "lúc nãy"), at))
    }

    @Test
    fun `distance has one decimal with a comma`() {
        assertEquals("3,2", TrackingUi.distance(3.2))
        assertEquals("3,0", TrackingUi.distance(2.96))
        assertEquals("0,3", TrackingUi.distance(0.25))
        assertEquals("12,5", TrackingUi.distance(12.46))
    }

    @Test
    fun `a late answer never moves the marker back`() {
        val old = tracking(capturedAt = "2026-11-18T06:52:00Z")
        val new = tracking(capturedAt = "2026-11-18T06:52:10Z")
        assertSame(new, TrackingUi.newer(old, new))
        assertSame(new, TrackingUi.newer(new, old))
        assertSame(old, TrackingUi.newer(null, old))
    }
}
