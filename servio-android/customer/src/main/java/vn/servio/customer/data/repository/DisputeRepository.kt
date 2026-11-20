package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.CreateDisputeBody
import vn.servio.customer.data.model.Dispute
import vn.servio.customer.data.remote.DisputeApi
import javax.inject.Inject
import javax.inject.Singleton

/** #146 (CS-33) and #147 (the dispute lines of CS-20). */
interface DisputeRepository {
    suspend fun create(orderId: String, body: CreateDisputeBody): ApiResult<Dispute>

    suspend fun list(orderId: String): ApiResult<List<Dispute>>
}

@Singleton
class RemoteDisputeRepository @Inject constructor(
    private val api: DisputeApi,
) : DisputeRepository {
    override suspend fun create(orderId: String, body: CreateDisputeBody): ApiResult<Dispute> = apiCall { api.create(orderId, body) }

    override suspend fun list(orderId: String): ApiResult<List<Dispute>> = apiCall { api.list(orderId) }
}
