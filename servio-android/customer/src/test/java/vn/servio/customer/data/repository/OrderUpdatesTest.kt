package vn.servio.customer.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderUpdatesTest {

    private fun payload(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `order id is read from OrderStatusChanged`() {
        val event = payload("""{"orderId":"01A1EAB3-5BDC-75D2-8704-E48663625F6A","assignmentId":"a1","status":"ACCEPTED","assignmentStatus":"ACCEPTED","changedAt":"2026-11-18T03:00:00Z","changedBy":"PARTNER"}""")
        assertEquals("01A1EAB3-5BDC-75D2-8704-E48663625F6A", OrderUpdates.orderIdOf(event))
        assertNull(OrderUpdates.orderIdOf(payload("""{"status":"ACCEPTED"}""")))
        assertNull(OrderUpdates.orderIdOf(payload("""{"orderId":""}""")))
        assertNull(OrderUpdates.orderIdOf(payload("""{"orderId":42}""")))
    }

    @Test
    fun `a screen keeps the changes of its order and every reconnect`() {
        val order = "01a1eab3-5bdc-75d2-8704-e48663625f6a"
        assertTrue(OrderUpdates.concerns("01A1EAB3-5BDC-75D2-8704-E48663625F6A", order))
        assertTrue(OrderUpdates.concerns(null, order))
        assertFalse(OrderUpdates.concerns("01a1eab3-0000-0000-0000-000000000000", order))
    }

    @Test
    fun `changes within 500 ms give one reload`() = runTest {
        val signals = MutableSharedFlow<Unit>()
        var reloads = 0
        val job = launch { signals.reloadCoalesced { reloads++ } }
        runCurrent()

        repeat(4) {
            signals.emit(Unit)
            advanceTimeBy(100)
        }
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, reloads)

        // A later change gives its own reload.
        signals.emit(Unit)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(2, reloads)
        job.cancel()
    }

    @Test
    fun `a change during a reload gives exactly one more reload`() = runTest {
        val signals = MutableSharedFlow<Unit>()
        var reloads = 0
        val job = launch {
            signals.reloadCoalesced {
                reloads++
                kotlinx.coroutines.delay(1_000)
            }
        }
        runCurrent()
        signals.emit(Unit)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, reloads)
        signals.emit(Unit)
        signals.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, reloads)
        job.cancel()
    }
}
