package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.AppConfig
import vn.servio.customer.data.remote.AppConfigApi
import javax.inject.Inject
import javax.inject.Singleton

/** Order rules of #107. Asked each time a screen needs them (an admin may change them at any time); no cache. */
interface AppConfigRepository {
    suspend fun getConfig(): ApiResult<AppConfig>
}

@Singleton
class RemoteAppConfigRepository @Inject constructor(
    private val api: AppConfigApi,
) : AppConfigRepository {
    override suspend fun getConfig(): ApiResult<AppConfig> = apiCall { api.getAppConfig() }
}
