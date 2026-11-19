package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * #87 GET /wallet/me for the customer. [debtBalance]: unpaid cancellation fees; [pendingPayment]: the part already
 * declared as transferred and waiting for the admin (AW-18); [maxDebt]: above it the customer cannot post a request.
 * [availableBalance] is always 0 for a customer.
 */
@Serializable
data class Wallet(
    val availableBalance: Long = 0,
    val debtBalance: Long = 0,
    val pendingPayment: Long = 0,
    val maxDebt: Long? = null,
)

/**
 * #155 one cancellation fee still owed (kind CANCELLATION_FEE). [id] goes into debtIds of #156.
 * [pendingTransactionCode] is set while a declared transfer for it waits for the admin.
 */
@Serializable
data class Debt(
    val id: String,
    val kind: String = "",
    val amount: Long = 0,
    val description: String = "",
    val orderCode: String? = null,
    val pendingTransactionCode: String? = null,
    val createdAt: String = "",
) {
    val isDeclared: Boolean get() = !pendingTransactionCode.isNullOrEmpty()
}

/** #156 body. [evidenceUrl]: the receipt photo uploaded with purpose DISPUTE_EVIDENCE. The server computes the amount. */
@Serializable
data class DebtPaymentBody(
    val debtIds: List<String>,
    val bankTransferReference: String,
    val evidenceUrl: String,
)

/** #156 result: a DEBT_PAYMENT transaction in [status] PENDING until the admin confirms it. */
@Serializable
data class DebtPaymentResult(
    val transactionId: String,
    val code: String = "",
    val amount: Long = 0,
    val status: String = "",
    val bankTransferReference: String = "",
    val createdAt: String = "",
)
