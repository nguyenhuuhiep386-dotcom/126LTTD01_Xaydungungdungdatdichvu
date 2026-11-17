package vn.servio.customer.ui.confirmquote

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.servio.customer.ui.confirmquote.ConfirmFailure.Changed
import vn.servio.customer.ui.confirmquote.ConfirmFailure.Dialog
import vn.servio.customer.ui.confirmquote.ConfirmFailure.DialogKind
import vn.servio.customer.ui.confirmquote.ConfirmFailure.Leave
import vn.servio.customer.ui.confirmquote.ConfirmFailure.Retry

class ConfirmFailureTest {

    @Test
    fun aChangedQuoteIsReloaded() {
        assertEquals(Changed(Dialog(DialogKind.CHANGED, "")), ConfirmFailure.of("RESOURCE_VERSION_CONFLICT", "server"))
    }

    @Test
    fun theScreenSaysItWhenTheRequestAlreadyHasAPartnerOrIsGone() {
        assertEquals(Leave(Dialog(DialogKind.ALREADY_MATCHED, "")), ConfirmFailure.of("QUOTE_ALREADY_ACCEPTED", "server"))
        assertEquals(Leave(Dialog(DialogKind.NOT_FOUND, "")), ConfirmFailure.of("NOT_FOUND", "server"))
    }

    @Test
    fun partnerAndRequestProblemsKeepTheServerMessage() {
        listOf("PARTNER_NOT_ELIGIBLE", "PARTNER_CAPACITY_EXCEEDED", "SCHEDULE_CONFLICT").forEach { code ->
            assertEquals(Leave(Dialog(DialogKind.PARTNER_UNAVAILABLE, "why")), ConfirmFailure.of(code, "why"))
        }
        listOf("REQUEST_EXPIRED", "REQUEST_NOT_OPEN").forEach { code ->
            assertEquals(Leave(Dialog(DialogKind.REQUEST_CLOSED, "why")), ConfirmFailure.of(code, "why"))
        }
    }

    @Test
    fun anythingElseCanBeTriedAgain() {
        listOf("NETWORK_ERROR", "UNKNOWN", "VALIDATION_ERROR", "SOMETHING_NEW").forEach { code ->
            assertEquals(Retry("msg"), ConfirmFailure.of(code, "msg"))
        }
    }
}
