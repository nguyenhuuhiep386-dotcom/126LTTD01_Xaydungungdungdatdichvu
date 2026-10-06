package vn.servio.customer.data.repository

import kotlinx.coroutines.delay
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.ProfileSummary
import javax.inject.Inject
import javax.inject.Singleton

/** CS-29 data source. Work package KH-1 adds RemoteProfileRepository and switches the binding in AppModule. */
interface ProfileRepository {
    suspend fun getProfileSummary(): ApiResult<ProfileSummary>
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
