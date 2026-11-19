package vn.servio.customer.data.repository

import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.customer.data.model.PendingReview
import vn.servio.customer.data.model.ReviewBody
import vn.servio.customer.data.model.SubmittedReview
import vn.servio.customer.data.remote.OrderApi
import javax.inject.Inject
import javax.inject.Singleton

/** Reviews the customer writes (CS-26, the "Đánh giá đối tác" button of CS-20). */
interface ReviewRepository {
    /** #100. */
    suspend fun pendingReviews(): ApiResult<List<PendingReview>>

    /** #95. */
    suspend fun submit(orderId: String, body: ReviewBody): ApiResult<SubmittedReview>
}

@Singleton
class RemoteReviewRepository @Inject constructor(
    private val api: OrderApi,
) : ReviewRepository {
    override suspend fun pendingReviews(): ApiResult<List<PendingReview>> = apiCall { api.getPendingReviews() }

    override suspend fun submit(orderId: String, body: ReviewBody): ApiResult<SubmittedReview> = apiCall { api.submitReview(orderId, body) }
}
