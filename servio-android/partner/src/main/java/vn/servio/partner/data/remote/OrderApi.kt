package vn.servio.partner.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.PagedResult
import vn.servio.partner.data.model.ArrivalConfirmation
import vn.servio.partner.data.model.ArriveRequest
import vn.servio.partner.data.model.ArriveResult
import vn.servio.partner.data.model.CancelResult
import vn.servio.partner.data.model.CompleteWorkRequest
import vn.servio.partner.data.model.CreateExtraChargeRequest
import vn.servio.partner.data.model.CustomerNoShowRequest
import vn.servio.partner.data.model.ExtraCharge
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.model.OrderStatusChanged
import vn.servio.partner.data.model.OrderSummary
import vn.servio.partner.data.model.PartnerCancelRequest
import vn.servio.partner.data.model.RejectAssignmentRequest
import vn.servio.partner.data.model.StartMovingRequest
import vn.servio.partner.data.model.StartWorkRequest
import vn.servio.partner.data.model.TimelineEntry

// Paths are relative to BASE_URL + "api/v1/". Endpoint numbers (#n) refer to spec section 6.

/** Orders of the partner: list, detail, accept/reject, moving, arriving, cancelling (DT-4). */
interface OrderApi {
    /** #62. `status`: ACTIVE (includes DISPUTED), COMPLETED or CANCELLED; null = all. */
    @GET("orders/me")
    suspend fun list(
        @Query("status") status: String?,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): ApiResponse<PagedResult<OrderSummary>>

    /** #63 */
    @GET("orders/{id}")
    suspend fun get(@Path("id") id: String): ApiResponse<OrderDetail>

    /** #64 */
    @GET("orders/{id}/timeline")
    suspend fun timeline(@Path("id") id: String): ApiResponse<List<TimelineEntry>>

    /** #65 */
    @POST("orders/{id}/assignments/{aid}/accept")
    suspend fun accept(@Path("id") id: String, @Path("aid") assignmentId: String): ApiResponse<OrderStatusChanged>

    /** #66 (204) */
    @POST("orders/{id}/assignments/{aid}/reject")
    suspend fun reject(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
        @Body body: RejectAssignmentRequest,
    ): Response<Unit>

    /** #67 */
    @POST("orders/{id}/assignments/{aid}/start-moving")
    suspend fun startMoving(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
        @Body body: StartMovingRequest,
    ): ApiResponse<OrderStatusChanged>

    /** #68 GPS check-in: ≤ 200 m, accuracy ≤ 100 m, no mock location. */
    @POST("orders/{id}/assignments/{aid}/arrive")
    suspend fun arrive(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
        @Body body: ArriveRequest,
    ): ApiResponse<ArriveResult>

    /** #178 ask the customer to confirm the arrival (waits up to 300 s, max 3 times). */
    @POST("orders/{id}/assignments/{aid}/arrival-confirmation-requests")
    suspend fun requestArrivalConfirmation(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
    ): ApiResponse<ArrivalConfirmation>

    /** #152 the partner cancels their part (ACCEPTED, ON_THE_WAY or ARRIVED). */
    @POST("orders/{id}/assignments/{aid}/cancel")
    suspend fun cancel(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
        @Body body: PartnerCancelRequest,
    ): ApiResponse<OrderStatusChanged>

    /** #69 ARRIVED → IN_PROGRESS with the before photos. */
    @POST("orders/{id}/assignments/{aid}/start-work")
    suspend fun startWork(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
        @Body body: StartWorkRequest,
    ): ApiResponse<OrderStatusChanged>

    /** #70 IN_PROGRESS → COMPLETED_BY_PARTNER with the after photos. */
    @POST("orders/{id}/assignments/{aid}/complete")
    suspend fun complete(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
        @Body body: CompleteWorkRequest,
    ): ApiResponse<OrderStatusChanged>

    /** #73 */
    @POST("orders/{id}/extra-charges")
    suspend fun createExtraCharge(@Path("id") id: String, @Body body: CreateExtraChargeRequest): ApiResponse<ExtraCharge>

    /** #180 */
    @POST("orders/{id}/assignments/{aid}/customer-no-show")
    suspend fun customerNoShow(
        @Path("id") id: String,
        @Path("aid") assignmentId: String,
        @Body body: CustomerNoShowRequest,
    ): ApiResponse<CancelResult>
}
