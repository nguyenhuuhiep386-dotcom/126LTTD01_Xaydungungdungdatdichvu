package vn.servio.customer.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * GET /users/me (#9), /users/me/addresses (#12), /wallet/me (#87) and /notifications/unread-count (#104) in parallel.
 * The screen fails only if #9 or #12 fails; #87 and #104 failing leave their counter null ("–").
 */
@Singleton
class RemoteProfileRepository @Inject constructor(
    private val userApi: UserApi,
    private val addressApi: AddressApi,
    private val walletRepository: WalletRepository,
    private val notificationRepository: NotificationRepository,
) : ProfileRepository {

    override suspend fun getProfileSummary(): ApiResult<ProfileSummary> = coroutineScope {
        val meCall = async { apiCall { userApi.getMe() } }
        val addressesCall = async { apiCall { addressApi.getAddresses() } }
        val walletCall = async { walletRepository.getWallet() }
        val unreadCall = async { notificationRepository.unreadCount() }
        val me = meCall.await()
        val addresses = addressesCall.await()
        val wallet = walletCall.await()
        val unread = unreadCall.await()
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
                        wallet = (wallet as? ApiResult.Success)?.data,
                        addressCount = (addresses as ApiResult.Success).data.size,
                        unreadNotificationCount = (unread as? ApiResult.Success)?.data?.total,
                    ),
                )
            }
        }
    }
}
