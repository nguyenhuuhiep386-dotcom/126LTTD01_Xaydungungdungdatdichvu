package vn.servio.partner.data.repository

import kotlinx.coroutines.flow.Flow
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.core.network.map
import vn.servio.core.realtime.Realtime
import vn.servio.partner.data.model.FeedFilter
import vn.servio.partner.data.model.FeedItem
import vn.servio.partner.data.model.PartnerQuote
import vn.servio.partner.data.model.PartnerRequestView
import vn.servio.partner.data.model.QuoteStatus
import vn.servio.partner.data.model.SubmitQuoteRequest
import vn.servio.partner.data.model.WithdrawQuoteRequest
import vn.servio.partner.data.remote.FeedApi
import javax.inject.Inject
import javax.inject.Singleton

/** PS-07 newsfeed (#46 + hub NewPost), PS-10 detail (#38), PS-11/PS-12 quotes (#50, #52, #53). */
interface FeedRepository {
    suspend fun getFeed(filter: FeedFilter = FeedFilter()): ApiResult<List<FeedItem>>

    /** New posts pushed by the server while connected (only to partners that are Online). */
    fun newPosts(): Flow<FeedItem>

    /** Emits after the feed hub reconnects: reload, events sent while offline are lost. */
    fun reconnected(): Flow<Unit>

    suspend fun getRequest(requestId: String): ApiResult<PartnerRequestView>
    suspend fun submitQuote(requestId: String, request: SubmitQuoteRequest): ApiResult<PartnerQuote>
    suspend fun listMyQuotes(status: QuoteStatus?): ApiResult<List<PartnerQuote>>
    suspend fun withdrawQuote(quoteId: String): ApiResult<Unit>
}

@Singleton
class RemoteFeedRepository @Inject constructor(
    private val api: FeedApi,
    private val realtime: Realtime,
) : FeedRepository {

    override suspend fun getFeed(filter: FeedFilter): ApiResult<List<FeedItem>> = apiCall {
        api.getFeed(
            categoryIds = filter.categoryIds.toList().ifEmpty { null },
            maxDistanceKm = filter.maxDistanceKm?.toDouble(),
            sort = filter.sort.name,
        )
    }.map { it.items }

    override fun newPosts(): Flow<FeedItem> {
        realtime.feed.start()
        return realtime.feed.listen(EVENT_NEW_POST, FeedItem.serializer())
    }

    override fun reconnected(): Flow<Unit> = realtime.feed.reconnected

    override suspend fun getRequest(requestId: String) = apiCall { api.getRequest(requestId) }

    override suspend fun submitQuote(requestId: String, request: SubmitQuoteRequest) =
        apiCall { api.submitQuote(requestId, request) }

    override suspend fun listMyQuotes(status: QuoteStatus?): ApiResult<List<PartnerQuote>> =
        apiCall { api.listMyQuotes(status?.name) }.map { it.items }

    override suspend fun withdrawQuote(quoteId: String) =
        apiCallNoContent { api.withdrawQuote(quoteId, WithdrawQuoteRequest()) }

    private companion object {
        const val EVENT_NEW_POST = "NewPost"
    }
}
