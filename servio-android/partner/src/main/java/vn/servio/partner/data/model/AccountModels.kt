package vn.servio.partner.data.model

import kotlinx.serialization.Serializable

// DT-6: wallet and commission debt (#87, #88, #155, #156), reviews (#95), notifications (#101–#104).

/** #87. Partner: [debtBalance] = commission debt (COD); above [maxDebt] new work is blocked. */
@Serializable
data class Wallet(
    val availableBalance: Long = 0,
    val debtBalance: Long = 0,
    val pendingPayment: Long = 0,
    val maxDebt: Long = 200_000,
)

/** #88 ledger line. [direction]: CREDIT or DEBIT; [bucket]: AVAILABLE or DEBT. */
@Serializable
data class WalletTransaction(
    val id: String,
    val type: String,
    val direction: String,
    val bucket: String,
    val amount: Long,
    val balanceAfter: Long,
    val description: String,
    val orderId: String? = null,
    val createdAt: String,
)

/** #155. For the partner: one COMMISSION row; [pendingTransactionCode] while a declared transfer waits for the admin. */
@Serializable
data class Debt(
    val id: String,
    val kind: String,
    val amount: Long,
    val description: String,
    val orderCode: String? = null,
    val pendingTransactionCode: String? = null,
    val createdAt: String,
)

/** #156. The partner sends no debtIds (pays the whole debt); the server computes the amount. */
@Serializable
data class DeclareDebtPaymentRequest(
    val debtIds: List<String>? = null,
    val bankTransferReference: String,
    val evidenceUrl: String,
)

@Serializable
data class DebtPayment(
    val transactionId: String,
    val code: String,
    val amount: Long,
    val status: String,
    val bankTransferReference: String,
    val createdAt: String,
)

/** #95 partner → customer: overall rating and quick tags (PAID_ON_TIME, ACCURATE_DESCRIPTION, FRIENDLY). */
@Serializable
data class CreateReviewRequest(
    val assignmentId: String,
    val rating: Int,
    val comment: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class Review(val id: String, val orderId: String, val rating: Int)

/** #101 row. [deepLink] like `orders/{id}` or `service-requests/{id}`. */
@Serializable
data class AppNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val deepLink: String? = null,
    val isRead: Boolean = false,
    val createdAt: String,
)

@Serializable
data class NotificationUnread(val total: Int)
