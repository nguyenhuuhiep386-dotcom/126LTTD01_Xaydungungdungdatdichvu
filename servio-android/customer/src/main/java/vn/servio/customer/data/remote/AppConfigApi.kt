package vn.servio.customer.data.remote

import retrofit2.http.GET
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.AppConfig

/** Order rules shown by the app. Paths are relative to BASE_URL + "api/v1/". */
interface AppConfigApi {
    /** #107, no token needed. */
    @GET("app-config")
    suspend fun getAppConfig(): ApiResponse<AppConfig>
}
