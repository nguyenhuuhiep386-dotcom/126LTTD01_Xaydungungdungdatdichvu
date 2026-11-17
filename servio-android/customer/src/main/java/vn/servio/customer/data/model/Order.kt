package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * #43 body, course scope: exactly one quote, cash only. [quoteVersions] maps the quote id to its rowVersion from #42;
 * a changed quote gives 409 RESOURCE_VERSION_CONFLICT. No request revision (the server checks it itself), no promotion,
 * no carry-over fee. [paymentMethod] has no default on purpose: the app's Json does not encode defaults.
 */
@Serializable
data class AcceptQuotesBody(
    val quoteIds: List<String>,
    val quoteVersions: Map<String, String>,
    val paymentMethod: String,
) {
    companion object {
        const val CASH = "CASH"

        fun one(quoteId: String, rowVersion: String) =
            AcceptQuotesBody(listOf(quoteId), mapOf(quoteId to rowVersion), CASH)
    }
}

/** The part of #43's answer (the order detail of #63) CS-18 needs to open the order. */
@Serializable
data class CreatedOrder(
    val id: String,
    val code: String,
)
