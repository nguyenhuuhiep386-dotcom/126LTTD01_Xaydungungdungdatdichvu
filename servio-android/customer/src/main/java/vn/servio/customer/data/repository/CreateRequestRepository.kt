package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.CreateServiceRequestRequest
import vn.servio.customer.data.model.CreatedRequest
import vn.servio.customer.data.remote.ServiceRequestApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posting a request (CS-11, #36). Kept apart from [ServiceRequestRepository] (CS-14 list, still fake until KH-3)
 * so the fake list does not need a fake "create".
 */
interface CreateRequestRepository {
    suspend fun createRequest(request: CreateServiceRequestRequest): ApiResult<CreatedRequest>
}

/** Real API. #36 exists on the BE-2 branch; until it is merged into develop the server answers 404. */
@Singleton
class RemoteCreateRequestRepository @Inject constructor(
    private val api: ServiceRequestApi,
) : CreateRequestRepository {

    override suspend fun createRequest(request: CreateServiceRequestRequest) = apiCall { api.create(request) }
}
