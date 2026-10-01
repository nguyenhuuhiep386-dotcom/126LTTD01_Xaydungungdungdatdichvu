package vn.servio.partner.data.repository

import kotlinx.coroutines.delay
import vn.servio.core.network.ApiResult
import vn.servio.partner.data.model.FeedItem
import javax.inject.Inject
import javax.inject.Singleton

/** PS-07 data source. Work package DT-2 adds RemoteFeedRepository (#46 + hub NewPost) and switches the binding. */
interface FeedRepository {
    suspend fun getFeed(): ApiResult<List<FeedItem>>
}

/** Sample feed so the partner app can be demoed without the backend (BC2). */
@Singleton
class FakeFeedRepository @Inject constructor() : FeedRepository {

    override suspend fun getFeed(): ApiResult<List<FeedItem>> {
        delay(600)
        return ApiResult.Success(SAMPLE)
    }

    private companion object {
        val SAMPLE = listOf(
            FeedItem("1", "Máy lạnh phòng ngủ chảy nước", "Vệ sinh máy lạnh", "Linh Chiểu, Thủ Đức", 1.2,
                200_000, 400_000, 3, "2026-10-01T08:30:00+07:00", hasImages = true),
            FeedItem("2", "Thay vòi sen nhà tắm", "Sửa ống nước", "Linh Chiểu, Thủ Đức", 1.2,
                null, 300_000, 0, "2026-10-01T09:10:00+07:00"),
            FeedItem("3", "Sửa ổ cắm bị chập", "Sửa điện dân dụng", "Hiệp Phú, Thủ Đức", 3.8,
                150_000, 250_000, 1, "2026-10-01T07:15:00+07:00"),
            FeedItem("4", "Lắp đèn trần phòng khách", "Sửa điện dân dụng", "Bình Thọ, Thủ Đức", 6.5,
                null, null, 2, "2026-09-30T19:40:00+07:00", hasImages = true),
        )
    }
}
