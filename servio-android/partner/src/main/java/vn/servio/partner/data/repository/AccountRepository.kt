package vn.servio.partner.data.repository

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.core.network.map
import vn.servio.partner.data.model.AppNotification
import vn.servio.partner.data.model.CreateReviewRequest
import vn.servio.partner.data.model.Debt
import vn.servio.partner.data.model.DebtPayment
import vn.servio.partner.data.model.DeclareDebtPaymentRequest
import vn.servio.partner.data.model.NotificationUnread
import vn.servio.partner.data.model.Review
import vn.servio.partner.data.model.Wallet
import vn.servio.partner.data.model.WalletTransaction
import javax.inject.Inject
import javax.inject.Singleton

/** Wallet, reviews and notifications (DT-6). Paths are relative to BASE_URL + "api/v1/". */
interface AccountApi {
    @GET("wallet/me")
    suspend fun wallet(): ApiResponse<Wallet>

    @GET("wallet/transactions")
    suspend fun transactions(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 30): ApiResponse<PagedResult<WalletTransaction>>

    @GET("wallet/debts")
    suspend fun debts(): ApiResponse<List<Debt>>

    @POST("wallet/debt-payments")
    suspend fun declarePayment(@Body body: DeclareDebtPaymentRequest): ApiResponse<DebtPayment>

    @POST("orders/{id}/reviews")
    suspend fun review(@Path("id") orderId: String, @Body body: CreateReviewRequest): ApiResponse<Review>

    @GET("notifications")
    suspend fun notifications(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 50): ApiResponse<PagedResult<AppNotification>>

    @POST("notifications/{id}/read")
    suspend fun markRead(@Path("id") id: String): Response<Unit>

    @POST("notifications/read-all")
    suspend fun markAllRead(): Response<Unit>

    @GET("notifications/unread-count")
    suspend fun unreadCount(): ApiResponse<NotificationUnread>
}

interface AccountRepository {
    suspend fun wallet(): ApiResult<Wallet>
    suspend fun transactions(): ApiResult<List<WalletTransaction>>
    suspend fun debts(): ApiResult<List<Debt>>
    suspend fun declarePayment(reference: String, evidenceUrl: String): ApiResult<DebtPayment>
    suspend fun reviewCustomer(orderId: String, request: CreateReviewRequest): ApiResult<Review>
    suspend fun notifications(): ApiResult<List<AppNotification>>
    suspend fun markRead(id: String): ApiResult<Unit>
    suspend fun markAllRead(): ApiResult<Unit>
    suspend fun unreadNotifications(): ApiResult<Int>
}

@Singleton
class RemoteAccountRepository @Inject constructor(
    private val api: AccountApi,
) : AccountRepository {
    override suspend fun wallet() = apiCall { api.wallet() }
    override suspend fun transactions() = apiCall { api.transactions() }.map { it.items }
    override suspend fun debts() = apiCall { api.debts() }
    override suspend fun declarePayment(reference: String, evidenceUrl: String) =
        apiCall { api.declarePayment(DeclareDebtPaymentRequest(bankTransferReference = reference, evidenceUrl = evidenceUrl)) }
    override suspend fun reviewCustomer(orderId: String, request: CreateReviewRequest) = apiCall { api.review(orderId, request) }
    override suspend fun notifications() = apiCall { api.notifications() }.map { it.items }
    override suspend fun markRead(id: String) = apiCallNoContent { api.markRead(id) }
    override suspend fun markAllRead() = apiCallNoContent { api.markAllRead() }
    override suspend fun unreadNotifications() = apiCall { api.unreadCount() }.map { it.total }
}
