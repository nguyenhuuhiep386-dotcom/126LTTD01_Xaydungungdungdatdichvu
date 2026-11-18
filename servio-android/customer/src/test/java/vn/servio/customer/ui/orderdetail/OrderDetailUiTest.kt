package vn.servio.customer.ui.orderdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.customer.data.model.OrderActor
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderParty
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.ui.orderdetail.OrderDetailUi.Actions
import vn.servio.customer.ui.orderdetail.OrderDetailUi.Line
import vn.servio.customer.ui.orderdetail.OrderDetailUi.Note
import vn.servio.customer.ui.orderdetail.OrderDetailUi.StatusArea
import vn.servio.customer.ui.orderdetail.OrderDetailUi.StepKind
import vn.servio.customer.ui.orderdetail.OrderDetailUi.StepState

class OrderDetailUiTest {

    private val created = "2026-11-18T03:00:00Z"
    private val deadline = "2026-11-18T03:10:00Z"
    private val accepted = "2026-11-18T03:02:00Z"
    private val onTheWay = "2026-11-18T06:40:00Z"
    private val arrived = "2026-11-18T07:00:00Z"
    private val started = "2026-11-18T07:05:00Z"
    private val completed = "2026-11-18T09:00:00Z"

    /** The primary part as far as [status] goes, with the times the server sets on the way. */
    private fun part(status: OrderStatus, phone: String? = "0987000001") = OrderAssignment(
        id = "a1",
        status = status,
        isPrimary = true,
        partner = OrderParty("p1", "Thợ Một", rating = 4.8, phoneNumber = phone),
        amount = 450_000,
        acceptanceDeadlineAt = deadline,
        acceptedAt = accepted.takeIf { status.ordinal >= OrderStatus.ACCEPTED.ordinal && status != OrderStatus.CANCELLED },
        onTheWayAt = onTheWay.takeIf { status.ordinal >= OrderStatus.ON_THE_WAY.ordinal && status != OrderStatus.CANCELLED },
        arrivedAt = arrived.takeIf { status.ordinal >= OrderStatus.ARRIVED.ordinal && status != OrderStatus.CANCELLED },
        startedAt = started.takeIf { status.ordinal >= OrderStatus.IN_PROGRESS.ordinal && status != OrderStatus.CANCELLED },
    )

    private fun order(status: OrderStatus, part: OrderAssignment = part(status), conversation: String? = "c1") = OrderDetail(
        id = "o1",
        code = "OD2611180010",
        status = status,
        paymentMethod = "CASH",
        scheduledStartAt = "2026-11-19T07:00:00Z",
        assignments = listOf(part),
        conversationId = conversation,
        autoConfirmAt = "2026-11-19T09:00:00Z".takeIf { status == OrderStatus.COMPLETED_BY_PARTNER },
        completedAt = completed.takeIf { status == OrderStatus.COMPLETED },
        createdAt = created,
    )

    // region Status area

    @Test
    fun `pending shows the countdown banner with the window from the server times`() {
        assertEquals(StatusArea.Pending(deadline, 10), OrderDetailUi.statusArea(order(OrderStatus.PENDING)))
    }

    @Test
    fun `pending without a deadline keeps the banner but no window`() {
        val noDeadline = order(OrderStatus.PENDING, part(OrderStatus.PENDING).copy(acceptanceDeadlineAt = null))
        assertEquals(StatusArea.Pending(null, null), OrderDetailUi.statusArea(noDeadline))
    }

    @Test
    fun `each status has its line and time`() {
        assertEquals(StatusArea.Card(OrderStatus.ACCEPTED, Line.SCHEDULED, "2026-11-19T07:00:00Z"), OrderDetailUi.statusArea(order(OrderStatus.ACCEPTED)))
        assertEquals(StatusArea.Card(OrderStatus.ON_THE_WAY, Line.ON_THE_WAY, onTheWay), OrderDetailUi.statusArea(order(OrderStatus.ON_THE_WAY)))
        assertEquals(StatusArea.Card(OrderStatus.ARRIVED, Line.ARRIVED, arrived), OrderDetailUi.statusArea(order(OrderStatus.ARRIVED)))
        assertEquals(StatusArea.Card(OrderStatus.IN_PROGRESS, Line.STARTED, started), OrderDetailUi.statusArea(order(OrderStatus.IN_PROGRESS)))
        assertEquals(
            StatusArea.Card(OrderStatus.COMPLETED_BY_PARTNER, Line.AUTO_CONFIRM, "2026-11-19T09:00:00Z"),
            OrderDetailUi.statusArea(order(OrderStatus.COMPLETED_BY_PARTNER)),
        )
        assertEquals(StatusArea.Card(OrderStatus.COMPLETED, Line.COMPLETED, completed), OrderDetailUi.statusArea(order(OrderStatus.COMPLETED)))
        val cancelled = order(OrderStatus.CANCELLED).copy(cancelledAt = "2026-11-18T04:00:00Z")
        assertEquals(StatusArea.Card(OrderStatus.CANCELLED, Line.CANCELLED, "2026-11-18T04:00:00Z"), OrderDetailUi.statusArea(cancelled))
        assertEquals(StatusArea.Disputed, OrderDetailUi.statusArea(order(OrderStatus.DISPUTED, part(OrderStatus.IN_PROGRESS))))
        assertEquals(StatusArea.Card(OrderStatus.UNKNOWN, Line.NONE, null), OrderDetailUi.statusArea(order(OrderStatus.UNKNOWN)))
    }

    // endregion

    // region Actions

    @Test
    fun `pending hides chat and call behind the phone note and can be cancelled`() {
        assertEquals(
            Actions(phoneNote = true, chat = false, call = false, cancel = true, confirm = false),
            OrderDetailUi.actions(order(OrderStatus.PENDING, part(OrderStatus.PENDING, phone = null))),
        )
    }

    @Test
    fun `accepted to arrived chat, call and cancel`() {
        listOf(OrderStatus.ACCEPTED, OrderStatus.ON_THE_WAY, OrderStatus.ARRIVED).forEach {
            assertEquals(Actions(phoneNote = false, chat = true, call = true, cancel = true, confirm = false), OrderDetailUi.actions(order(it)))
        }
    }

    @Test
    fun `from in progress on there is no cancel, and confirm only when the partner reported done`() {
        assertEquals(Actions(false, true, true, false, false), OrderDetailUi.actions(order(OrderStatus.IN_PROGRESS)))
        assertEquals(Actions(false, true, true, false, true), OrderDetailUi.actions(order(OrderStatus.COMPLETED_BY_PARTNER)))
        // The server stops sending the phone once the work is done.
        assertEquals(Actions(false, true, false, false, false), OrderDetailUi.actions(order(OrderStatus.COMPLETED, part(OrderStatus.COMPLETED, phone = null))))
        assertEquals(Actions(false, false, false, false, false), OrderDetailUi.actions(order(OrderStatus.CANCELLED, part(OrderStatus.CANCELLED, null), conversation = null)))
        assertEquals(Actions(false, true, false, false, false), OrderDetailUi.actions(order(OrderStatus.DISPUTED, part(OrderStatus.IN_PROGRESS, null))))
    }

    @Test
    fun `cancel label is free only when 151 said 0`() {
        assertTrue(OrderDetailUi.cancelIsFree(0))
        assertFalse(OrderDetailUi.cancelIsFree(20_000))
        assertFalse(OrderDetailUi.cancelIsFree(null))
    }

    // endregion

    // region Timeline

    private fun states(status: OrderStatus, o: OrderDetail = order(status)) = OrderDetailUi.timeline(o).steps.map { it.state }

    @Test
    fun `pending is waiting at step 2 with its deadline`() {
        val timeline = OrderDetailUi.timeline(order(OrderStatus.PENDING))
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.TODO, StepState.TODO, StepState.TODO, StepState.TODO), timeline.steps.map { it.state })
        assertEquals(Note.At(created), timeline.steps[0].note)
        assertTrue(timeline.steps[1].waiting)
        assertEquals(Note.ExpiresAt(deadline), timeline.steps[1].note)
        assertEquals(Note.None, timeline.steps[2].note)
        assertNull(timeline.cancelled)
    }

    @Test
    fun `the step reached is current and shows its time`() {
        val accepted = OrderDetailUi.timeline(order(OrderStatus.ACCEPTED)).steps
        assertEquals(StepState.CURRENT, accepted[1].state)
        assertFalse(accepted[1].waiting)
        assertEquals(Note.At(this.accepted), accepted[1].note)

        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.CURRENT, StepState.TODO, StepState.TODO, StepState.TODO), states(OrderStatus.ON_THE_WAY))
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE, StepState.CURRENT, StepState.TODO, StepState.TODO), states(OrderStatus.ARRIVED))
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE, StepState.DONE, StepState.CURRENT, StepState.TODO), states(OrderStatus.IN_PROGRESS))
        assertEquals(Note.At(started), OrderDetailUi.timeline(order(OrderStatus.IN_PROGRESS)).steps[4].note)
    }

    @Test
    fun `completed by partner has step 5 done and step 6 waiting for the customer`() {
        val steps = OrderDetailUi.timeline(order(OrderStatus.COMPLETED_BY_PARTNER)).steps
        assertEquals(StepState.DONE, steps[4].state)
        assertEquals(StepKind.COMPLETED, steps[5].kind)
        assertEquals(StepState.CURRENT, steps[5].state)
        assertEquals(Note.AwaitingConfirm("2026-11-19T09:00:00Z"), steps[5].note)
    }

    @Test
    fun `completed has every step done with the completion time`() {
        val steps = OrderDetailUi.timeline(order(OrderStatus.COMPLETED)).steps
        assertTrue(steps.all { it.state == StepState.DONE })
        assertEquals(Note.At(completed), steps[5].note)
    }

    @Test
    fun `cancelled keeps only what happened plus the red line`() {
        val part = part(OrderStatus.CANCELLED).copy(acceptedAt = accepted, onTheWayAt = onTheWay, cancelReason = "  ")
        val cancelled = order(OrderStatus.CANCELLED, part).copy(
            cancelledAt = "2026-11-18T06:50:00Z",
            cancelledBy = OrderActor.CUSTOMER,
            cancelReason = "Tôi muốn đổi lịch",
        )
        val timeline = OrderDetailUi.timeline(cancelled)
        assertEquals(listOf(StepKind.CREATED, StepKind.PARTNER, StepKind.ON_THE_WAY), timeline.steps.map { it.kind })
        assertTrue(timeline.steps.all { it.state == StepState.DONE })
        assertEquals(Note.At(onTheWay), timeline.steps[2].note)
        assertEquals(OrderDetailUi.Cancelled("2026-11-18T06:50:00Z", OrderActor.CUSTOMER, "Tôi muốn đổi lịch"), timeline.cancelled)
    }

    @Test
    fun `cancelled while pending by the system has no actor-less parts invented`() {
        val expired = order(OrderStatus.CANCELLED).copy(cancelledAt = "2026-11-18T03:10:05Z", cancelledBy = OrderActor.SYSTEM)
        val timeline = OrderDetailUi.timeline(expired)
        assertEquals(listOf(StepKind.CREATED), timeline.steps.map { it.kind })
        assertEquals(OrderDetailUi.Cancelled("2026-11-18T03:10:05Z", OrderActor.SYSTEM, null), timeline.cancelled)

        val bare = OrderDetailUi.timeline(order(OrderStatus.CANCELLED)).cancelled
        assertEquals(OrderDetailUi.Cancelled(null, null, null), bare)
    }

    @Test
    fun `disputed follows its primary part`() {
        val disputed = order(OrderStatus.DISPUTED, part(OrderStatus.IN_PROGRESS))
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE, StepState.DONE, StepState.CURRENT, StepState.TODO), states(OrderStatus.DISPUTED, disputed))
        assertNull(OrderDetailUi.timeline(disputed).cancelled)
    }

    // endregion
}
