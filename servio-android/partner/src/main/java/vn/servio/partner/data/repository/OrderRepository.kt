package vn.servio.partner.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonObject
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.core.network.map
import vn.servio.core.realtime.Realtime
import vn.servio.partner.data.location.Fix
import vn.servio.partner.data.model.ArrivalConfirmation
import vn.servio.partner.data.model.ArriveRequest
import vn.servio.partner.data.model.ArriveResult
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.model.OrderStatusChanged
import vn.servio.partner.data.model.OrderSummary
import vn.servio.partner.data.model.PartnerCancelRequest
import vn.servio.partner.data.model.RejectAssignmentRequest
import vn.servio.partner.data.model.StartMovingRequest
import vn.servio.partner.data.model.TimelineEntry
import vn.servio.partner.data.remote.OrderApi
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** PS-13 → PS-19 orders. Live changes come from /hubs/orders; screens reload #63 when told something changed. */
interface OrderRepository {
    enum class ListFilter { ACTIVE, COMPLETED, CANCELLED }

    suspend fun list(filter: ListFilter?): ApiResult<List<OrderSummary>>
    suspend fun get(orderId: String): ApiResult<OrderDetail>
    suspend fun timeline(orderId: String): ApiResult<List<TimelineEntry>>
    suspend fun accept(orderId: String, assignmentId: String): ApiResult<OrderStatusChanged>
    suspend fun reject(orderId: String, assignmentId: String, reason: String): ApiResult<Unit>
    suspend fun startMoving(orderId: String, assignmentId: String, from: Fix?): ApiResult<OrderStatusChanged>
    suspend fun arrive(orderId: String, assignmentId: String, fix: Fix): ApiResult<ArriveResult>
    suspend fun requestArrivalConfirmation(orderId: String, assignmentId: String): ApiResult<ArrivalConfirmation>
    suspend fun cancel(orderId: String, assignmentId: String, reason: String): ApiResult<OrderStatusChanged>

    /** Ids of orders that changed (status, new order to confirm, extra charge answered, arrival answered). */
    fun changes(): Flow<String?>

    /** Customer's answer to "Nhờ khách xác nhận đã đến". */
    fun arrivalResolved(): Flow<ArrivalConfirmation>

    /** UpdateLocation on /hubs/orders; false when not connected or the point was refused. */
    suspend fun sendLocation(assignmentId: String, fix: Fix): Boolean
}

@Singleton
class RemoteOrderRepository @Inject constructor(
    private val api: OrderApi,
    private val realtime: Realtime,
) : OrderRepository {

    override suspend fun list(filter: OrderRepository.ListFilter?) =
        apiCall { api.list(filter?.name) }.map { it.items }

    override suspend fun get(orderId: String) = apiCall { api.get(orderId) }

    override suspend fun timeline(orderId: String) = apiCall { api.timeline(orderId) }

    override suspend fun accept(orderId: String, assignmentId: String) = apiCall { api.accept(orderId, assignmentId) }

    override suspend fun reject(orderId: String, assignmentId: String, reason: String) =
        apiCallNoContent { api.reject(orderId, assignmentId, RejectAssignmentRequest(reason)) }

    override suspend fun startMoving(orderId: String, assignmentId: String, from: Fix?) = apiCall {
        api.startMoving(orderId, assignmentId, StartMovingRequest(from?.latitude, from?.longitude, from?.accuracy?.toInt()))
    }

    override suspend fun arrive(orderId: String, assignmentId: String, fix: Fix) = apiCall {
        api.arrive(
            orderId,
            assignmentId,
            ArriveRequest(fix.latitude, fix.longitude, fix.accuracy.toInt(), isoOf(fix.capturedAtMillis), fix.isMock),
        )
    }

    override suspend fun requestArrivalConfirmation(orderId: String, assignmentId: String) =
        apiCall { api.requestArrivalConfirmation(orderId, assignmentId) }

    override suspend fun cancel(orderId: String, assignmentId: String, reason: String) =
        apiCall { api.cancel(orderId, assignmentId, PartnerCancelRequest(reason)) }

    override fun changes(): Flow<String?> {
        val hub = realtime.orders
        hub.start()
        return merge(
            hub.listen("OrderStatusChanged", JsonObject.serializer()).map(::orderIdOf),
            hub.listen("QuoteAccepted", JsonObject.serializer()).map(::orderIdOf),
            hub.listen("ExtraChargeResponded", JsonObject.serializer()).map(::orderIdOf),
            hub.listen("ArrivalConfirmationResolved", JsonObject.serializer()).map(::orderIdOf),
            hub.reconnected.map { null },
        )
    }

    override fun arrivalResolved(): Flow<ArrivalConfirmation> {
        realtime.orders.start()
        return realtime.orders.listen("ArrivalConfirmationResolved", ArrivalConfirmation.serializer())
    }

    override suspend fun sendLocation(assignmentId: String, fix: Fix): Boolean {
        realtime.orders.start()
        return realtime.orders.invoke(
            Boolean::class.javaObjectType,
            "UpdateLocation",
            assignmentId,
            fix.latitude,
            fix.longitude,
            fix.accuracy.toInt(),
            isoOf(fix.capturedAtMillis),
        ) == true
    }

    private fun orderIdOf(payload: JsonObject): String? =
        (payload["orderId"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun isoOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
}
