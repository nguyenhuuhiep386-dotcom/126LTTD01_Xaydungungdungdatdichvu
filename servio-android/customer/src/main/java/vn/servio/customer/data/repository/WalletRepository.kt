package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.Debt
import vn.servio.customer.data.model.DebtPaymentBody
import vn.servio.customer.data.model.DebtPaymentResult
import vn.servio.customer.data.model.Wallet
import vn.servio.customer.data.remote.WalletApi
import javax.inject.Inject
import javax.inject.Singleton

/** Cancellation fees the customer owes and their declared bank transfers (CS-29, CS-30). */
interface WalletRepository {
    /** #87. */
    suspend fun getWallet(): ApiResult<Wallet>

    /** #155. */
    suspend fun getDebts(): ApiResult<List<Debt>>

    /** #156. */
    suspend fun declarePayment(body: DebtPaymentBody): ApiResult<DebtPaymentResult>
}

@Singleton
class RemoteWalletRepository @Inject constructor(
    private val api: WalletApi,
) : WalletRepository {
    override suspend fun getWallet(): ApiResult<Wallet> = apiCall { api.getWallet() }

    override suspend fun getDebts(): ApiResult<List<Debt>> = apiCall { api.getDebts() }

    override suspend fun declarePayment(body: DebtPaymentBody): ApiResult<DebtPaymentResult> = apiCall { api.declarePayment(body) }
}
