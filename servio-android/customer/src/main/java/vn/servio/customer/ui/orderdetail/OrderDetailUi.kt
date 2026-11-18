package vn.servio.customer.ui.orderdetail

import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.ExtraChargeStatus
import vn.servio.customer.data.model.OrderActor
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.ui.order.OrderTexts

/**
 * What CS-20 shows for an order, decided from #63 only (pure, unit tested). Times are the server's ISO strings; the
 * fragment formats them and hides a line whose time is missing.
 */
object OrderDetailUi {

    /** Order statuses the customer can still cancel (2.5.2): CS-20 asks #151 for the fee and shows the cancel button. */
    val CANCELLABLE = setOf(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.ON_THE_WAY, OrderStatus.ARRIVED)

    fun canCancel(status: OrderStatus): Boolean = status in CANCELLABLE

    // region Status area (top of CS-20)

    sealed interface StatusArea {
        /** CS-20a banner: countdown to [deadlineAt]; [windowMinutes] = deadline − creation, null when unknown. */
        data class Pending(val deadlineAt: String?, val windowMinutes: Int?) : StatusArea

        /** White card: chip of [status] and one line "[line] [at]". */
        data class Card(val status: OrderStatus, val line: Line, val at: String?) : StatusArea

        /** "Khiếu nại đang được xử lý". */
        data object Disputed : StatusArea
    }

    /** The sentence under the chip; [NONE] = chip alone. */
    enum class Line { SCHEDULED, ON_THE_WAY, ARRIVED, STARTED, AUTO_CONFIRM, COMPLETED, CANCELLED, NONE }

    fun statusArea(order: OrderDetail): StatusArea {
        val primary = order.primary
        return when (order.status) {
            OrderStatus.PENDING -> StatusArea.Pending(
                primary?.acceptanceDeadlineAt,
                OrderTexts.confirmWindowMinutes(order.createdAt, primary?.acceptanceDeadlineAt),
            )
            OrderStatus.ACCEPTED -> StatusArea.Card(order.status, Line.SCHEDULED, order.scheduledStartAt)
            OrderStatus.ON_THE_WAY -> StatusArea.Card(order.status, Line.ON_THE_WAY, primary?.onTheWayAt)
            OrderStatus.ARRIVED -> StatusArea.Card(order.status, Line.ARRIVED, primary?.arrivedAt)
            OrderStatus.IN_PROGRESS -> StatusArea.Card(order.status, Line.STARTED, primary?.startedAt ?: order.startedAt)
            OrderStatus.COMPLETED_BY_PARTNER -> StatusArea.Card(order.status, Line.AUTO_CONFIRM, order.autoConfirmAt)
            OrderStatus.COMPLETED -> StatusArea.Card(order.status, Line.COMPLETED, order.completedAt)
            OrderStatus.CANCELLED -> StatusArea.Card(order.status, Line.CANCELLED, order.cancelledAt ?: primary?.cancelledAt)
            OrderStatus.DISPUTED -> StatusArea.Disputed
            else -> StatusArea.Card(order.status, Line.NONE, null)
        }
    }

    // endregion

    // region Actions

    /**
     * [phoneNote]: "Số điện thoại hiện sau khi đối tác nhận đơn" (PENDING). [chat] needs a conversation, [call] a
     * phone number (the server only sends it from ACCEPTED until the work is done, F-ORD-06). [confirm] opens CS-23.
     */
    data class Actions(
        val phoneNote: Boolean,
        val chat: Boolean,
        val call: Boolean,
        val cancel: Boolean,
        val confirm: Boolean,
    )

    fun actions(order: OrderDetail): Actions {
        val pending = order.status == OrderStatus.PENDING
        return Actions(
            phoneNote = pending,
            chat = !pending && !order.conversationId.isNullOrEmpty(),
            call = !pending && !order.primary?.partner?.phoneNumber.isNullOrBlank(),
            cancel = canCancel(order.status),
            confirm = order.status == OrderStatus.COMPLETED_BY_PARTNER,
        )
    }

    /** Label of the cancel button: "(miễn phí)" only when #151 said 0; unknown fee (not loaded, failed) → plain. */
    fun cancelIsFree(fee: Long?): Boolean = fee == 0L

    // endregion

    // region Timeline (6 steps of Figma)

    enum class StepKind { CREATED, PARTNER, ON_THE_WAY, ARRIVED, IN_PROGRESS, COMPLETED }

    enum class StepState { DONE, CURRENT, TODO }

    /** Second line of a step. */
    sealed interface Note {
        /** [pendingCharges] > 0 only on the current "Đang thực hiện" step: " · N phát sinh chờ duyệt". */
        data class At(val at: String?, val pendingCharges: Int = 0) : Note

        /** PENDING, step 2: "Hết hạn lúc HH:mm". */
        data class ExpiresAt(val at: String?) : Note

        /** COMPLETED_BY_PARTNER, step 6: "Chờ bạn xác nhận · tự xác nhận lúc …". */
        data class AwaitingConfirm(val autoConfirmAt: String?) : Note

        data object None : Note
    }

    /** [waiting]: step 2 is still "Chờ đối tác xác nhận" (PENDING), otherwise "Đối tác xác nhận". */
    data class Step(val kind: StepKind, val state: StepState, val note: Note, val waiting: Boolean = false)

    /** Red line of a cancelled order; each part is left out when the server did not send it. */
    data class Cancelled(val at: String?, val by: OrderActor?, val reason: String?)

    data class Timeline(val steps: List<Step>, val cancelled: Cancelled?)

    /**
     * Step times: order createdAt, then acceptedAt, onTheWayAt, arrivedAt, startedAt of the primary part, then the
     * order's completedAt. The step reached is CURRENT (yellow), earlier ones DONE, later ones TODO.
     * DISPUTED follows its primary part (the work status does not change during a dispute).
     * CANCELLED keeps only the steps that happened, all DONE, plus the red line.
     */
    fun timeline(order: OrderDetail): Timeline {
        val primary = order.primary
        val times = listOf(
            order.createdAt.takeIf { it.isNotEmpty() },
            primary?.acceptedAt,
            primary?.onTheWayAt,
            primary?.arrivedAt,
            primary?.startedAt ?: order.startedAt,
            order.completedAt ?: primary?.completedAt,
        )
        val kinds = StepKind.entries
        val progress = if (order.status == OrderStatus.DISPUTED) primary?.status ?: OrderStatus.UNKNOWN else order.status

        if (progress == OrderStatus.CANCELLED) {
            val steps = kinds.indices
                .filter { it == 0 || times[it] != null }
                .map { Step(kinds[it], StepState.DONE, Note.At(times[it])) }
            return Timeline(
                steps,
                Cancelled(
                    order.cancelledAt ?: primary?.cancelledAt,
                    order.cancelledBy,
                    (order.cancelReason ?: primary?.cancelReason)?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }

        val current: Int? = when (progress) {
            OrderStatus.PENDING, OrderStatus.ACCEPTED -> 1
            OrderStatus.ON_THE_WAY -> 2
            OrderStatus.ARRIVED -> 3
            OrderStatus.IN_PROGRESS -> 4
            OrderStatus.COMPLETED_BY_PARTNER, OrderStatus.AWAITING_PAYMENT -> 5
            else -> null
        }
        val steps = kinds.mapIndexed { index, kind ->
            val state = when {
                current == null -> if (progress == OrderStatus.COMPLETED || index == 0 || times[index] != null) StepState.DONE else StepState.TODO
                index < current -> StepState.DONE
                index == current -> StepState.CURRENT
                else -> StepState.TODO
            }
            val note = when {
                state == StepState.TODO -> Note.None
                progress == OrderStatus.PENDING && kind == StepKind.PARTNER -> Note.ExpiresAt(primary?.acceptanceDeadlineAt)
                progress == OrderStatus.COMPLETED_BY_PARTNER && kind == StepKind.COMPLETED -> Note.AwaitingConfirm(order.autoConfirmAt)
                progress == OrderStatus.AWAITING_PAYMENT && kind == StepKind.COMPLETED -> Note.None
                kind == StepKind.IN_PROGRESS && state == StepState.CURRENT -> Note.At(times[index], pendingCharges(order).size)
                else -> Note.At(times[index])
            }
            Step(kind, state, note, waiting = progress == OrderStatus.PENDING && kind == StepKind.PARTNER)
        }
        return Timeline(steps, null)
    }

    // endregion

    // region Extra charges (CS-22)

    /** Order statuses in which #74 accepts an answer (the server checks the assignment: ARRIVED or IN_PROGRESS). */
    val CHARGE_STATUSES = setOf(OrderStatus.ARRIVED, OrderStatus.IN_PROGRESS)

    /**
     * Charges waiting for the customer, oldest first (by createdAt; the server already sends them in that order, an
     * unreadable time keeps its place at the end). None outside [CHARGE_STATUSES]: a disputed or cancelled order
     * cannot be answered, so CS-20 neither counts nor opens them.
     */
    fun pendingCharges(order: OrderDetail): List<ExtraCharge> {
        if (order.status !in CHARGE_STATUSES) return emptyList()
        return order.extraCharges
            .filter { it.status == ExtraChargeStatus.PENDING }
            .sortedWith(compareBy(nullsLast()) { OrderTexts.instant(it.createdAt) })
    }

    /**
     * The charge CS-22 opens: the oldest pending one, skipping those the customer closed in this visit of CS-20
     * ([closed], ids compared without case) unless [includeClosed] (a tap on the warning line). [held]: a charge whose
     * answer just failed with a 409/404, left out until the reload shows where it stands.
     */
    fun chargeToOpen(order: OrderDetail, closed: Set<String>, includeClosed: Boolean, held: String? = null): ExtraCharge? =
        pendingCharges(order).firstOrNull { charge ->
            !charge.id.equals(held, ignoreCase = true) &&
                (includeClosed || closed.none { it.equals(charge.id, ignoreCase = true) })
        }

    /**
     * Price block of CS-22, all from #63: "Giá báo" = subTotal, "Phát sinh đã duyệt" = extraChargeTotal (null = row
     * hidden when 0), "Phát sinh này" = the charge, "Tổng mới" = totalAmount + the charge. Only a preview: once
     * approved, CS-20 shows the totals the server computed.
     */
    data class Bill(val quoted: Long, val approvedExtras: Long?, val charge: Long, val newTotal: Long)

    fun bill(order: OrderDetail, charge: ExtraCharge): Bill = Bill(
        quoted = order.pricing.subTotal,
        approvedExtras = order.pricing.extraChargeTotal.takeIf { it > 0 },
        charge = charge.amount,
        newTotal = order.pricing.totalAmount + charge.amount,
    )

    /** What a failed #74 means for CS-22. */
    enum class ChargeFailure {
        /** Already answered, the work moved on, or answered at the same time elsewhere: close, say so, reload. */
        HANDLED,

        /** The order is being disputed: close and say it cannot be answered now. */
        DISPUTED,

        /** No such charge (any more): close and reload. */
        NOT_FOUND,

        /** Network and the rest: keep the sheet with the message, the customer can try again. */
        OTHER,
    }

    fun chargeFailure(code: String): ChargeFailure = when (code) {
        "INVALID_STATUS_TRANSITION", "CONFLICT" -> ChargeFailure.HANDLED
        "ORDER_DISPUTED" -> ChargeFailure.DISPUTED
        "NOT_FOUND" -> ChargeFailure.NOT_FOUND
        else -> ChargeFailure.OTHER
    }

    // endregion
}
