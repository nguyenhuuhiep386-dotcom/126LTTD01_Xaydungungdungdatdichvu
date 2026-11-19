package vn.servio.customer.ui.requests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary

class RequestCardTest {

    private fun request(
        status: ServiceRequestStatus = ServiceRequestStatus.OPEN,
        quotes: Int = 0,
        type: ScheduleType = ScheduleType.NOW,
        start: String? = null,
        end: String? = null,
        expires: String? = null,
        broadcast: Int = 0,
    ) = ServiceRequestSummary(
        id = "r1", code = "SR1", title = "Ổ điện hỏng", categoryId = "c1", categoryName = "Sửa điện dân dụng", status = status,
        quoteCount = quotes, broadcastCount = broadcast, addressSnapshot = "Đà Nẵng", scheduleType = type,
        scheduledStartAt = start, scheduledEndAt = end, expiresAt = expires, createdAt = "2026-11-17T01:00:00+00:00",
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
    fun theEndIsTheServersScheduledEndAt() {
        val booked = request(type = ScheduleType.SCHEDULED, start = "2026-10-17T07:00:00+00:00", end = "2026-10-17T09:00:00+00:00")
        assertEquals("2026-10-17T09:00:00+00:00", RequestCard(booked, null).scheduledEndAt)
        // No end from the server: nothing is computed, the card shows the start only.
        assertNull(RequestCard(request(type = ScheduleType.SCHEDULED, start = "2026-10-17T07:00:00+00:00"), null).scheduledEndAt)
    }

    @Test
    fun anOpenRequestWithoutQuotesSaysWhoItWasSentTo() {
        assertEquals(RequestFooter.SentTo(7), RequestCard(request(broadcast = 7), null).footer)
    }

    @Test
    fun quotesOrNoBroadcastCountKeepTheCountdown() {
        assertEquals(RequestFooter.Expiry, RequestCard(request(quotes = 2, broadcast = 7), null).footer)
        assertEquals(RequestFooter.Expiry, RequestCard(request(broadcast = 0), null).footer)
    }

    @Test
    fun onlyAnOpenRequestHasALastLine() {
        listOf(ServiceRequestStatus.DRAFT, ServiceRequestStatus.MATCHED, ServiceRequestStatus.EXPIRED, ServiceRequestStatus.CANCELLED)
            .forEach { assertEquals(RequestFooter.None, RequestCard(request(it, broadcast = 7), null).footer) }
    }

    @Test
    fun theGroupNameIsUsedWhenKnown() {
        assertEquals("Điện – Nước", RequestCard(request(), "Điện – Nước").categoryLabel)
        assertEquals("Sửa điện dân dụng", RequestCard(request(), null).categoryLabel)
    }
}
