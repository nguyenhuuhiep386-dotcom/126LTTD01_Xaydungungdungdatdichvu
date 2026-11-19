package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.data.remote.ServerConfigApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #107. Every screen asks again when it opens (an admin may change the rules at any time); the last answer of this
 * session is kept in memory only, never on disk.
 */
interface ServerConfigRepository {
    /** The last config received in this session, null before the first one. */
    val latest: ServerConfig?

    /** Asks the server; when that fails, the last config of this session is returned instead, if there is one. */
    suspend fun getConfig(): ApiResult<ServerConfig>
}

@Singleton
class RemoteServerConfigRepository @Inject constructor(
    private val api: ServerConfigApi,
) : ServerConfigRepository {

    @Volatile
    override var latest: ServerConfig? = null
        private set

    override suspend fun getConfig(): ApiResult<ServerConfig> =
        when (val result = apiCall { api.getServerConfig() }) {
            is ApiResult.Success -> result.also { latest = it.data }
            is ApiResult.Failure -> latest?.let { ApiResult.Success(it) } ?: result
        }
}
