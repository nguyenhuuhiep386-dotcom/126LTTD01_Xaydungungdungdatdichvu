package vn.servio.customer.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import vn.servio.core.data.remote.UserApi
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.ProfileSummary
import vn.servio.customer.data.remote.AddressApi
import javax.inject.Inject
import javax.inject.Singleton

/** CS-29 data source. Bound to RemoteProfileRepository in AppModule. */
interface ProfileRepository {
    suspend fun getProfileSummary(): ApiResult<ProfileSummary>
}

/**
 * Real API: GET /users/me (#9) and GET /users/me/addresses (#12) in parallel; the screen fails if either fails.
 * The fee (#156) and unread notification (#104) endpoints do not exist yet, so those counters stay null.
 */
@Singleton
class RemoteProfileRepository @Inject constructor(
    private val userApi: UserApi,
    private val addressApi: AddressApi,
) : ProfileRepository {

    override suspend fun getProfileSummary(): ApiResult<ProfileSummary> = coroutineScope {
        val meCall = async { apiCall { userApi.getMe() } }
        val addressesCall = async { apiCall { addressApi.getAddresses() } }
        val me = meCall.await()
        val addresses = addressesCall.await()
        when {
            me is ApiResult.Failure -> me
            addresses is ApiResult.Failure -> addresses
            else -> {
                val user = (me as ApiResult.Success).data
                ApiResult.Success(
                    ProfileSummary(
                        fullName = user.fullName,
                        phoneNumber = user.phoneNumber,
                        avatarUrl = user.avatarUrl,
                        outstandingFeeVnd = null,
                        addressCount = (addresses as ApiResult.Success).data.size,
                        unreadNotificationCount = null,
                    ),
                )
            }
        }
    }
}

/** Fake data matching the Figma frame of CS-29. */
@Singleton
class FakeProfileRepository @Inject constructor() : ProfileRepository {

    override suspend fun getProfileSummary(): ApiResult<ProfileSummary> {
        delay(600)
        return ApiResult.Success(SAMPLE)
    }

    private companion object {
        val SAMPLE = ProfileSummary(
            fullName = "Nguyễn Minh Anh",
            phoneNumber = "+84912345678",
            avatarUrl = null,
            outstandingFeeVnd = 20_000,
            addressCount = 3,
            unreadNotificationCount = 2,
        )
    }
}
