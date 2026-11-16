package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.remote.PartnerApi
import javax.inject.Inject
import javax.inject.Singleton

/** Public partner profiles (CS-16 skill chips, CS-17). */
interface PartnerRepository {
    /** #27. */
    suspend fun getPublicProfile(partnerId: String): ApiResult<PartnerPublic>
}

@Singleton
class RemotePartnerRepository @Inject constructor(
    private val api: PartnerApi,
) : PartnerRepository {
    override suspend fun getPublicProfile(partnerId: String): ApiResult<PartnerPublic> = apiCall { api.getPublic(partnerId) }
}
