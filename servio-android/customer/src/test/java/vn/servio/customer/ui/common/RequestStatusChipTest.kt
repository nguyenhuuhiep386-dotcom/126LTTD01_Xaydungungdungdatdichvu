package vn.servio.customer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.servio.customer.R
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.ui.common.RequestStatusChip.Icon
import vn.servio.customer.ui.common.RequestStatusChip.Look
import vn.servio.customer.ui.common.RequestStatusChip.Tone

class RequestStatusChipTest {

    @Test
    fun inProgressRequestsAreYellowWithAnIcon() {
        assertEquals(Look(R.string.requests_status_open, Tone.ACTIVE, Icon.CLOCK), RequestStatusChip.look(ServiceRequestStatus.OPEN))
        assertEquals(Look(R.string.requests_status_draft, Tone.ACTIVE, Icon.CLOCK), RequestStatusChip.look(ServiceRequestStatus.DRAFT))
        assertEquals(Look(R.string.requests_status_matched, Tone.ACTIVE, Icon.CHECK), RequestStatusChip.look(ServiceRequestStatus.MATCHED))
    }

    @Test
    fun closedRequestsAreNeutralWithoutIcon() {
        assertEquals(Look(R.string.requests_status_cancelled, Tone.CLOSED, Icon.NONE), RequestStatusChip.look(ServiceRequestStatus.CANCELLED))
        assertEquals(Look(R.string.requests_status_expired, Tone.CLOSED, Icon.NONE), RequestStatusChip.look(ServiceRequestStatus.EXPIRED))
        assertEquals(
            Look(R.string.requests_status_rejected, Tone.CLOSED, Icon.NONE),
            RequestStatusChip.look(ServiceRequestStatus.REJECTED_BY_MODERATION),
        )
    }

    @Test
    fun openLabelCanBeOverriddenByTheDetailScreen() {
        assertEquals(
            R.string.request_detail_status_open,
            RequestStatusChip.look(ServiceRequestStatus.OPEN, openLabel = R.string.request_detail_status_open).label,
        )
        // Only OPEN takes the override.
        assertEquals(
            R.string.requests_status_draft,
            RequestStatusChip.look(ServiceRequestStatus.DRAFT, openLabel = R.string.request_detail_status_open).label,
        )
    }
}
