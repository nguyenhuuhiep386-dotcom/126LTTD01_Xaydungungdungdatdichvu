package vn.servio.customer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.data.model.ArrivalConfirmationStatus
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.OrderStatusChange
import vn.servio.customer.data.model.PendingArrival
import java.time.Instant

class ArrivalRequestsTest {

    private var now = Instant.parse("2026-11-18T07:00:00Z")
    private val requests = ArrivalRequests { now }

    private fun request(id: String = "r1", expiresAt: String = "2026-11-18T07:05:00Z", order: String = "O1", status: ArrivalConfirmationStatus = ArrivalConfirmationStatus.PENDING) =
        ArrivalConfirmation(order, "A1", id, status, expiresAt)

    @Test
    fun `a request is kept for its order until it expires`() {
        requests.received(request())
        assertEquals("r1", requests.current("o1")?.requestId)
        now = Instant.parse("2026-11-18T07:04:59Z")
        assertEquals("r1", requests.current("O1")?.requestId)
        now = Instant.parse("2026-11-18T07:05:00Z")
        assertNull(requests.current("o1"))
        now = Instant.parse("2026-11-18T07:00:00Z")
        assertNull(requests.current("o1")) // dropped, not only hidden
    }

    @Test
    fun `a newer request replaces the older one, answered ones are dropped`() {
        requests.received(request("r1"))
        requests.received(request("r2"))
        assertEquals("r2", requests.current("o1")?.requestId)
        requests.answered("R1")
        assertEquals("r2", requests.current("o1")?.requestId)
        val before = requests.version.value
        requests.answered("r2")
        assertNull(requests.current("o1"))
        assertEquals(before + 1, requests.version.value)
    }

    @Test
    fun `only pending and unexpired requests are kept`() {
        requests.received(request(status = ArrivalConfirmationStatus.EXPIRED))
        requests.received(request(id = "r2", expiresAt = "2026-11-18T06:59:00Z"))
        requests.received(request(id = "r3", expiresAt = "không rõ"))
        assertNull(requests.current("o1"))
    }

    @Test
    fun `a status change drops it once its assignment is no longer on the way`() {
        requests.received(request())
        requests.statusChanged(OrderStatusChange("o1", "a1", OrderStatus.ON_THE_WAY, OrderStatus.ON_THE_WAY))
        requests.statusChanged(OrderStatusChange("o1", "other", OrderStatus.ARRIVED, OrderStatus.ARRIVED))
        requests.statusChanged(OrderStatusChange("o2", "a1", OrderStatus.CANCELLED, OrderStatus.CANCELLED))
        assertEquals("r1", requests.current("o1")?.requestId)
        requests.statusChanged(OrderStatusChange("o1", "a1", OrderStatus.ARRIVED, OrderStatus.ARRIVED))
        assertNull(requests.current("o1"))
    }

    @Test
    fun `sign-out drops every kept request`() {
        requests.received(request("r1", order = "O1"))
        requests.received(request("r2", order = "O2"))
        val before = requests.version.value
        requests.clear()
        assertNull(requests.current("o1"))
        assertNull(requests.current("o2"))
        assertEquals(before + 1, requests.version.value)
        requests.clear() // nothing left: no new version
        assertEquals(before + 1, requests.version.value)
    }

    @Test
    fun `a request restored from 63 is kept like one from the hub`() {
        val before = requests.version.value
        requests.restored(request())
        assertEquals("r1", requests.current("o1")?.requestId)
        assertEquals(before + 1, requests.version.value)
    }

    @Test
    fun `the same request from the hub and from 63 is kept once`() {
        requests.received(request("r1"))
        val before = requests.version.value
        requests.restored(request("R1"))
        assertEquals("r1", requests.current("o1")?.requestId)
        assertEquals(before, requests.version.value) // no second CS-20b
    }

    @Test
    fun `an answered request is not brought back by a 63 read before the answer reached the server`() {
        requests.received(request("r1"))
        requests.answered("r1")
        requests.restored(request("r1"))
        assertNull(requests.current("o1"))
    }

    @Test
    fun `an expired or older request of 63 is ignored`() {
        requests.restored(request("r1", expiresAt = "2026-11-18T06:59:00Z"))
        assertNull(requests.current("o1"))
        requests.received(request("r2", expiresAt = "2026-11-18T07:05:00Z"))
        requests.restored(request("r1", expiresAt = "2026-11-18T07:03:00Z"))
        assertEquals("r2", requests.current("o1")?.requestId)
    }

    @Test
    fun `pendingArrivals maps each waiting assignment of 63`() {
        val detail = OrderDetail(
            id = "O1", code = "OD1", status = OrderStatus.ON_THE_WAY,
            assignments = listOf(
                OrderAssignment("A1", OrderStatus.ON_THE_WAY, isPrimary = true, pendingArrivalConfirmation = PendingArrival("r1", "2026-11-18T07:05:00Z")),
                OrderAssignment("A2", OrderStatus.ON_THE_WAY),
            ),
        )
        assertEquals(
            listOf(ArrivalConfirmation("O1", "A1", "r1", ArrivalConfirmationStatus.PENDING, "2026-11-18T07:05:00Z")),
            OrderUpdates.pendingArrivals(detail),
        )
    }
}
