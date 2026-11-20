package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.CreateDisputeBody
import vn.servio.customer.data.model.Dispute

/** #146 / #147 for tests: [createAnswer] and [listAnswer] are what the server says; [created] records each #146 body. */
class FakeDisputeRepository(
    var listAnswer: ApiResult<List<Dispute>> = ApiResult.Success(emptyList()),
    var createAnswer: ApiResult<Dispute> = ApiResult.Success(Dispute(id = "d1", code = "DS2611200001", status = Dispute.STATUS_OPEN)),
) : DisputeRepository {
    val created = mutableListOf<Pair<String, CreateDisputeBody>>()

    override suspend fun create(orderId: String, body: CreateDisputeBody): ApiResult<Dispute> {
        created += orderId to body
        return createAnswer
    }

    override suspend fun list(orderId: String): ApiResult<List<Dispute>> = listAnswer
}
