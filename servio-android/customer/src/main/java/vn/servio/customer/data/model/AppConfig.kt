package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * GET /app-config (#107): the order rules the apps print, read by the server from SystemConfigs so a change in AW-15
 * shows without a new build. Fees in VND; [cancellationFeeAfterArrivedPercent] may have decimals (20 or 12.5).
 * CS-18 uses the accept window and the three cancellation fees; [noShowWaitMinutes] has no place in the customer app yet.
 */
@Serializable
data class AppConfig(
    val partnerAcceptTimeoutMinutes: Int,
    val cancellationFeeAfterOnTheWay: Long,
    val cancellationFeeAfterArrivedMin: Long,
    val cancellationFeeAfterArrivedPercent: Double,
    val noShowWaitMinutes: Int,
)
