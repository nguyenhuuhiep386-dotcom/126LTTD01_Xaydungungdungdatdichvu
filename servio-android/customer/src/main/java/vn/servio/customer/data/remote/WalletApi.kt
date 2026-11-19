package vn.servio.customer.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.Debt
import vn.servio.customer.data.model.DebtPaymentBody
import vn.servio.customer.data.model.DebtPaymentResult
import vn.servio.customer.data.model.Wallet

/** M6 for the customer: unpaid cancellation fees (CS-29, CS-30). Paths are relative to BASE_URL + "api/v1/". */
interface WalletApi {
    /** #87. */
    @GET("wallet/me")
    suspend fun getWallet(): ApiResponse<Wallet>

    /** #155, oldest first. */
    @GET("wallet/debts")
    suspend fun getDebts(): ApiResponse<List<Debt>>

    /**
     * #156: 201 with a PENDING transaction. No Idempotency-Key: a second send of the same debts fails with
     * 422 NOTHING_TO_PAY. 400 VALIDATION_ERROR (field bankTransferReference, debtIds or evidenceUrl).
     */
    @POST("wallet/debt-payments")
    suspend fun declarePayment(@Body body: DebtPaymentBody): ApiResponse<DebtPaymentResult>
}
