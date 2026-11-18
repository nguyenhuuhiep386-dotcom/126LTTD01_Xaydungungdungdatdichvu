package vn.servio.customer.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.AcceptQuotesBody
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.data.model.CancelOrderBody
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.CancelResult
import vn.servio.customer.data.model.CreatedOrder
import vn.servio.customer.data.model.CustomerWallet
import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.data.model.RespondArrivalBody
import vn.servio.customer.data.model.RespondExtraChargeBody
import vn.servio.customer.data.model.Tracking

/** Orders of the signed-in customer (BE-4). Paths are relative to BASE_URL + "api/v1/". */
interface OrderApi {
    /**
     * #43: 201 with the order detail. Sending the same quote again returns the same order, so a retry after a network
     * error is safe. Errors: 409 RESOURCE_VERSION_CONFLICT, QUOTE_ALREADY_ACCEPTED, SCHEDULE_CONFLICT, REQUEST_NOT_OPEN;
     * 422 PARTNER_NOT_ELIGIBLE, PARTNER_CAPACITY_EXCEEDED, REQUEST_EXPIRED; 404 NOT_FOUND.
     */
    @POST("service-requests/{id}/accept-quotes")
    suspend fun acceptQuotes(@Path("id") requestId: String, @Body body: AcceptQuotesBody): ApiResponse<CreatedOrder>

    /** #62, newest first. [status]: ACTIVE, COMPLETED or CANCELLED (the CS-19 tabs). */
    @GET("orders/me")
    suspend fun getMine(
        @Query("status") status: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): ApiResponse<PagedResult<OrderSummary>>

    /** #63. 404 NOT_FOUND for an order of someone else. */
    @GET("orders/{id}")
    suspend fun getDetail(@Path("id") orderId: String): ApiResponse<OrderDetail>

    /** #151 fee if cancelled now. 422 ORDER_NOT_CANCELLABLE once work started; 409 when already cancelled. */
    @GET("orders/{id}/cancel-preview")
    suspend fun cancelPreview(@Path("id") orderId: String): ApiResponse<CancelPreview>

    /** #72. 409 RESOURCE_VERSION_CONFLICT when the order changed after the preview; 422 ORDER_NOT_CANCELLABLE. */
    @POST("orders/{id}/cancel")
    suspend fun cancel(@Path("id") orderId: String, @Body body: CancelOrderBody): ApiResponse<CancelResult>

    /** #87, for the debt limit (maxDebt) CS-20c names. */
    /** #76: position, distance and ETA per live assignment; [assignmentId] narrows it to one. */
    @GET("orders/{id}/tracking")
    suspend fun getTracking(@Path("id") orderId: String, @Query("assignmentId") assignmentId: String?): ApiResponse<List<Tracking>>

    /** #179 answer to "Thợ báo đã đến". 409 CONFLICT when the request expired or was already answered. */
    @POST("orders/{id}/assignments/{aid}/arrival-confirmation-requests/{requestId}/respond")
    suspend fun respondArrival(
        @Path("id") orderId: String,
        @Path("aid") assignmentId: String,
        @Path("requestId") requestId: String,
        @Body body: RespondArrivalBody,
    ): ApiResponse<ArrivalConfirmation>

    /**
     * #74 approve or reject an extra charge. 409 INVALID_STATUS_TRANSITION when it was already answered or the work is
     * no longer ARRIVED / IN_PROGRESS, 409 ORDER_DISPUTED, 409 CONFLICT on a concurrent answer, 404 NOT_FOUND.
     */
    @POST("orders/{id}/extra-charges/{ecId}/respond")
    suspend fun respondExtraCharge(
        @Path("id") orderId: String,
        @Path("ecId") extraChargeId: String,
        @Body body: RespondExtraChargeBody,
    ): ApiResponse<ExtraCharge>

    @GET("wallet/me")
    suspend fun getWallet(): ApiResponse<CustomerWallet>
}
