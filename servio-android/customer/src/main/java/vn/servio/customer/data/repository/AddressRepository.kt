package vn.servio.customer.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import vn.servio.core.network.ApiResult
import vn.servio.core.network.ErrorMessages
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.core.network.map
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.model.toSaveRequest
import vn.servio.customer.data.remote.AddressApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Address book (CS-13, CS-12, CS-29). Bound to RemoteAddressRepository (#12–#15) in AppModule. */
interface AddressRepository {
    suspend fun getAddresses(): ApiResult<List<Address>>
    suspend fun getAddress(id: String): ApiResult<Address>

    /** Creates the address when [Address.id] is blank, otherwise updates it. */
    suspend fun saveAddress(address: Address): ApiResult<Address>
    suspend fun deleteAddress(id: String): ApiResult<Unit>
    suspend fun setDefault(id: String): ApiResult<Unit>

    companion object {
        const val MAX_ADDRESSES = 10
    }
}

/**
 * Real API (#12–#15). The backend has no "get one" or "set default" endpoint: [getAddress] searches the list,
 * [setDefault] sends PUT #14 with isDefault = true and the server clears the other defaults itself.
 */
@Singleton
class RemoteAddressRepository @Inject constructor(
    private val api: AddressApi,
) : AddressRepository {

    override suspend fun getAddresses() = apiCall { api.getAddresses() }

    override suspend fun getAddress(id: String): ApiResult<Address> = when (val result = getAddresses()) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> result.data.firstOrNull { it.id == id }?.let { ApiResult.Success(it) } ?: notFound()
    }

    override suspend fun saveAddress(address: Address) = apiCall {
        if (address.id.isBlank()) {
            api.createAddress(address.toSaveRequest())
        } else {
            api.updateAddress(address.id, address.toSaveRequest())
        }
    }

    override suspend fun deleteAddress(id: String) = apiCallNoContent { api.deleteAddress(id) }

    override suspend fun setDefault(id: String): ApiResult<Unit> = when (val result = getAddress(id)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> apiCall { api.updateAddress(id, result.data.copy(isDefault = true).toSaveRequest()) }.map { }
    }

    private fun notFound() = ApiResult.Failure(NOT_FOUND, ErrorMessages.forCode(NOT_FOUND, fallback = "Không tìm thấy địa chỉ"))

    private companion object {
        const val NOT_FOUND = "NOT_FOUND"
    }
}

/**
 * Fake data kept in memory for the whole app session, so changes made on CS-12 are visible when going back to CS-13.
 * Same rules as the backend: at most [AddressRepository.MAX_ADDRESSES] addresses, exactly one default while any exist.
 */
@Singleton
class FakeAddressRepository @Inject constructor() : AddressRepository {

    private val addresses = MutableStateFlow(SAMPLE)

    override suspend fun getAddresses(): ApiResult<List<Address>> {
        delay(600)
        return ApiResult.Success(addresses.value)
    }

    override suspend fun getAddress(id: String): ApiResult<Address> {
        delay(300)
        return addresses.value.firstOrNull { it.id == id }?.let { ApiResult.Success(it) } ?: notFound()
    }

    override suspend fun saveAddress(address: Address): ApiResult<Address> {
        delay(300)
        val current = addresses.value
        val isNew = address.id.isBlank()
        if (isNew && current.size >= AddressRepository.MAX_ADDRESSES) {
            return ApiResult.Failure(ADDRESS_LIMIT_REACHED, ErrorMessages.forCode(ADDRESS_LIMIT_REACHED))
        }
        val existing = current.firstOrNull { it.id == address.id }
        if (!isNew && existing == null) return notFound()

        // The first address and the current default always stay default.
        val saved = address.copy(
            id = if (isNew) UUID.randomUUID().toString() else address.id,
            isDefault = address.isDefault || current.isEmpty() || existing?.isDefault == true,
        )
        addresses.update { list ->
            val cleared = if (saved.isDefault) list.map { it.copy(isDefault = false) } else list
            if (isNew) cleared + saved else cleared.map { if (it.id == saved.id) saved else it }
        }
        return ApiResult.Success(saved)
    }

    override suspend fun deleteAddress(id: String): ApiResult<Unit> {
        delay(300)
        val removed = addresses.value.firstOrNull { it.id == id } ?: return notFound()
        addresses.update { list ->
            val remaining = list.filter { it.id != id }
            // Deleting the default promotes the first remaining address.
            if (removed.isDefault) remaining.mapIndexed { index, item -> item.copy(isDefault = index == 0) } else remaining
        }
        return ApiResult.Success(Unit)
    }

    override suspend fun setDefault(id: String): ApiResult<Unit> {
        delay(300)
        if (addresses.value.none { it.id == id }) return notFound()
        addresses.update { list -> list.map { it.copy(isDefault = it.id == id) } }
        return ApiResult.Success(Unit)
    }

    private fun notFound() = ApiResult.Failure(ErrorMessages.UNKNOWN, ErrorMessages.forCode(ErrorMessages.UNKNOWN))

    private companion object {
        const val ADDRESS_LIMIT_REACHED = "ADDRESS_LIMIT_REACHED"

        // Same person as FakeProfileRepository.
        const val RECEIVER_NAME = "Nguyễn Minh Anh"
        const val RECEIVER_PHONE = "+84912345678"

        val SAMPLE = listOf(
            Address("1", "Nhà", RECEIVER_NAME, RECEIVER_PHONE, "12 Võ Văn Ngân, P. Linh Chiểu, TP. Thủ Đức",
                10.850632, 106.771934, "Nhà cổng xanh, tầng 3", isDefault = true),
            Address("2", "Cơ quan", RECEIVER_NAME, RECEIVER_PHONE, "1 Võ Văn Ngân, P. Linh Chiểu, TP. Thủ Đức",
                10.849503, 106.773041, null, isDefault = false),
            Address("3", "Nhà bố mẹ", RECEIVER_NAME, RECEIVER_PHONE, "45 Lê Văn Việt, P. Hiệp Phú, TP. Thủ Đức",
                10.846112, 106.778520, null, isDefault = false),
        )
    }
}
