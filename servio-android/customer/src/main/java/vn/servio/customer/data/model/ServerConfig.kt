package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * GET /app-config (#107, extended by PR #32): the rules and contacts the app prints, read by the server from
 * SystemConfigs so a change in AW-15 shows without a new build. Named ServerConfig so it is not mistaken for
 * vn.servio.core.utils.AppConfig (flavor and version of the build). Fees in VND; [cancellationFeeAfterArrivedPercent]
 * may have decimals (20 or 12.5). No field has a default: a number the server did not send is never made up.
 */
@Serializable
data class ServerConfig(
    val partnerAcceptTimeoutMinutes: Int,
    val cancellationFeeAfterOnTheWay: Long,
    val cancellationFeeAfterArrivedMin: Long,
    val cancellationFeeAfterArrivedPercent: Double,
    /** No place in the customer app yet. */
    val noShowWaitMinutes: Int,
    /** CS-34 FAQ "Tự hoàn thành". */
    val autoConfirmHours: Int,
    /** CS-34 FAQ "Khiếu nại": how long after completion a complaint can be opened. */
    val disputeWindowHours: Int,
    /** No place in the customer app yet (CS-33 is not built). */
    val disputeResponseHours: Int,
    /** No place in the customer app yet: the review button follows #100. */
    val reviewWindowDays: Int,
    val posting: PostingRules,
    val cancellationFeeBank: BankAccount,
    val support: SupportContact,
)

/** CS-09 / CS-11. NOW = a slot of [windowHours] from now; SCHEDULED = start in [minLeadMinutes, maxLeadDays] ahead. */
@Serializable
data class PostingRules(
    val windowHours: Int,
    val minLeadMinutes: Int,
    val maxLeadDays: Int,
    /** A SCHEDULED request expires after this many hours or at its start, whichever comes first. */
    val expireHours: Int,
)

/** CS-30 bank transfer card. Fields the admin has not filled in (AW-15) are null. */
@Serializable
data class BankAccount(
    val bankName: String? = null,
    val accountNumber: String? = null,
    val accountHolder: String? = null,
    /** "SERVIO {phone}": the app puts the customer's phone number in place of {phone}. */
    val transferNoteTemplate: String? = null,
)

/** CS-34. Null when the admin has not filled it in: hidden. */
@Serializable
data class SupportContact(
    val hotline: String? = null,
    val hours: String? = null,
)
