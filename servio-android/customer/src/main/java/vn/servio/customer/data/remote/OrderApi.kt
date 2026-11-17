package vn.servio.customer.data.remote

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.AcceptQuotesBody
import vn.servio.customer.data.model.CreatedOrder

/** Orders of the signed-in customer (BE-4). Paths are relative to BASE_URL + "api/v1/". */
interface OrderApi {
    /**
     * #43: 201 with the order detail. Sending the same quote again returns the same order, so a retry after a network
     * error is safe. Errors: 409 RESOURCE_VERSION_CONFLICT, QUOTE_ALREADY_ACCEPTED, SCHEDULE_CONFLICT, REQUEST_NOT_OPEN;
     * 422 PARTNER_NOT_ELIGIBLE, PARTNER_CAPACITY_EXCEEDED, REQUEST_EXPIRED; 404 NOT_FOUND.
     */
    @POST("service-requests/{id}/accept-quotes")
    suspend fun acceptQuotes(@Path("id") requestId: String, @Body body: AcceptQuotesBody): ApiResponse<CreatedOrder>
}
