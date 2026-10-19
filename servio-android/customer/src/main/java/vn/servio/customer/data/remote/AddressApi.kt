package vn.servio.customer.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import vn.servio.core.network.ApiResponse
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.model.SaveAddressRequest

// Paths are relative to BASE_URL + "api/v1/". Endpoint numbers (#n) refer to spec section 6.

/** Address book of the signed-in customer (#12–#15). */
interface AddressApi {
    @GET("users/me/addresses")
    suspend fun getAddresses(): ApiResponse<List<Address>>

    @POST("users/me/addresses")
    suspend fun createAddress(@Body body: SaveAddressRequest): ApiResponse<Address>

    @PUT("users/me/addresses/{id}")
    suspend fun updateAddress(@Path("id") id: String, @Body body: SaveAddressRequest): ApiResponse<Address>

    @DELETE("users/me/addresses/{id}")
    suspend fun deleteAddress(@Path("id") id: String): Response<Unit>
}
