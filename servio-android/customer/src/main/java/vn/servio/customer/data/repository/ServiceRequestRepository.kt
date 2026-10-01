package vn.servio.customer.data.repository

import kotlinx.coroutines.delay
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import javax.inject.Inject
import javax.inject.Singleton

/** CS-14 data source. Work package KH-3 adds RemoteServiceRequestRepository (#37) and switches the binding in AppModule. */
interface ServiceRequestRepository {
    suspend fun getMyRequests(): ApiResult<List<ServiceRequestSummary>>
}

/**
 * REFERENCE PATTERN (fake data): same interface as the real repository, returns sample data after a short delay
 * so loading states are visible. Keep it for UI work and demos without a backend.
 */
@Singleton
class FakeServiceRequestRepository @Inject constructor() : ServiceRequestRepository {

    override suspend fun getMyRequests(): ApiResult<List<ServiceRequestSummary>> {
        delay(600)
        return ApiResult.Success(SAMPLE)
    }

    private companion object {
        val SAMPLE = listOf(
            ServiceRequestSummary("1", "SR2610010001", "Máy lạnh phòng ngủ chảy nước", "Vệ sinh máy lạnh",
                ServiceRequestStatus.OPEN, 3, "12 Võ Văn Ngân, Thủ Đức", "2026-10-01T08:30:00+07:00"),
            ServiceRequestSummary("2", "SR2610010002", "Thay vòi sen nhà tắm", "Sửa ống nước",
                ServiceRequestStatus.OPEN, 0, "12 Võ Văn Ngân, Thủ Đức", "2026-10-01T09:10:00+07:00"),
            ServiceRequestSummary("3", "SR2609280005", "Lắp camera cổng trước", "Lắp camera",
                ServiceRequestStatus.MATCHED, 4, "45 Lê Văn Việt, Thủ Đức", "2026-09-28T14:00:00+07:00"),
            ServiceRequestSummary("4", "SR2609200011", "Dọn nhà sau sửa chữa", "Tổng vệ sinh",
                ServiceRequestStatus.EXPIRED, 1, "45 Lê Văn Việt, Thủ Đức", "2026-09-20T07:45:00+07:00"),
        )
    }
}
