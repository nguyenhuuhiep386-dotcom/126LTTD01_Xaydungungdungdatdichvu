package vn.servio.customer.data.remote

import retrofit2.http.GET
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.ServerConfig

/** Rules and contacts shown by the app. Paths are relative to BASE_URL + "api/v1/". */
interface ServerConfigApi {
    /** #107, no token needed. */
    @GET("app-config")
    suspend fun getServerConfig(): ApiResponse<ServerConfig>
}
