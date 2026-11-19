package vn.servio.customer.ui.requests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import java.time.OffsetDateTime

class RequestCardTest {

    private fun request(
        status: ServiceRequestStatus = ServiceRequestStatus.OPEN,
        quotes: Int = 0,
        type: ScheduleType = ScheduleType.NOW,
        start: String? = null,
        expires: String? = null,
    ) = ServiceRequestSummary(
        id = "r1", code = "SR1", title = "Ổ điện hỏng", categoryId = "c1", categoryName = "Sửa điện dân dụng", status = status,
        quoteCount = quotes, addressSnapshot = "Đà Nẵng", scheduleType = type, scheduledStartAt = start, expiresAt = expires,
        createdAt = "2026-11-17T01:00:00+00:00",
    )

    @Test
    fun chipShowsQuotesOnlyForOpenRequests() {
        assertEquals(RequestChip.WaitingForQuotes, RequestChip.of(ServiceRequestStatus.OPEN, 0))
        assertEquals(RequestChip.Quotes(3), RequestChip.of(ServiceRequestStatus.OPEN, 3))
        assertEquals(RequestChip.Status(ServiceRequestStatus.DRAFT), RequestChip.of(ServiceRequestStatus.DRAFT, 0))
        assertEquals(RequestChip.Status(ServiceRequestStatus.MATCHED), RequestChip.of(ServiceRequestStatus.MATCHED, 1))
        assertEquals(RequestChip.Status(ServiceRequestStatus.CANCELLED), RequestChip.of(ServiceRequestStatus.CANCELLED, 2))
    }

    @Test
    fun aNowRequestEndsWhenItExpires() {
        // The server sets ExpiresAt = ScheduledEndAt for NOW.
        val card = RequestCard(request(start = "2026-11-17T01:00:00+00:00", expires = "2026-11-17T03:00:00+00:00"), null)
        assertEquals("2026-11-17T03:00:00+00:00", card.scheduledEndAt)
    }

    @Test
    fun aBookedSlotEndsTwoHoursAfterItsStart() {
        val card = RequestCard(request(type = ScheduleType.SCHEDULED, start = "2026-10-17T07:00:00.0000000+00:00", expires = "2026-10-16T09:00:00+00:00"), null)
        assertEquals(OffsetDateTime.parse("2026-10-17T09:00:00Z"), OffsetDateTime.parse(card.scheduledEndAt))
        assertNull(RequestCard(request(type = ScheduleType.SCHEDULED, start = null), null).scheduledEndAt)
        assertNull(RequestCard(request(type = ScheduleType.SCHEDULED, start = "17/10"), null).scheduledEndAt)
    }

    @Test
    fun onlyAnOpenRequestCountsDown() {
        assertTrue(RequestCard(request(ServiceRequestStatus.OPEN), null).showsExpiry)
        listOf(ServiceRequestStatus.DRAFT, ServiceRequestStatus.MATCHED, ServiceRequestStatus.EXPIRED, ServiceRequestStatus.CANCELLED)
            .forEach { assertFalse(RequestCard(request(it), null).showsExpiry) }
    }

    @Test
    fun theGroupNameIsUsedWhenKnown() {
        assertEquals("Điện – Nước", RequestCard(request(), "Điện – Nước").categoryLabel)
        assertEquals("Sửa điện dân dụng", RequestCard(request(), null).categoryLabel)
    }
}
