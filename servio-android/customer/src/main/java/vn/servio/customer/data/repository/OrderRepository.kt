package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.AcceptQuotesBody
import vn.servio.customer.data.model.CreatedOrder
import vn.servio.customer.data.remote.OrderApi
import javax.inject.Inject
import javax.inject.Singleton

/** Orders (CS-18 now; CS-19 … CS-23 with KH-5). */
interface OrderRepository {
    /** #43: choose [quoteId] at [rowVersion] (from #42), paid in cash. */
    suspend fun acceptQuote(requestId: String, quoteId: String, rowVersion: String): ApiResult<CreatedOrder>
}

@Singleton
class RemoteOrderRepository @Inject constructor(
    private val api: OrderApi,
) : OrderRepository {
    override suspend fun acceptQuote(requestId: String, quoteId: String, rowVersion: String): ApiResult<CreatedOrder> =
        apiCall { api.acceptQuotes(requestId, AcceptQuotesBody.one(quoteId, rowVersion)) }
}
