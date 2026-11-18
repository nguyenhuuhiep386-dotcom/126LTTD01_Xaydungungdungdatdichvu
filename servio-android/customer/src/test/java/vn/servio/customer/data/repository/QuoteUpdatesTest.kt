package vn.servio.customer.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteUpdatesTest {

    private fun payload(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val request = "01a1eab3-5c6a-7209-87fc-c300d5fe7f6d"

    @Test
    fun `NewQuote of this request matches, whatever the case of the id`() {
        val newQuote = payload("""{"id":"q1","serviceRequestId":"01A1EAB3-5C6A-7209-87FC-C300D5FE7F6D","amount":400000,"status":"PENDING"}""")
        assertTrue(QuoteUpdates.isFor(newQuote, request))
    }

    @Test
    fun `QuoteWithdrawn of this request matches`() {
        assertTrue(QuoteUpdates.isFor(payload("""{"serviceRequestId":"$request","quoteId":"q1"}"""), request))
    }

    @Test
    fun `events of another request are ignored`() {
        assertFalse(QuoteUpdates.isFor(payload("""{"serviceRequestId":"01a1eab3-0000-0000-0000-000000000000","quoteId":"q1"}"""), request))
    }

    @Test
    fun `payload without a usable id is ignored`() {
        assertFalse(QuoteUpdates.isFor(payload("""{"quoteId":"q1"}"""), request))
        assertFalse(QuoteUpdates.isFor(payload("""{"serviceRequestId":null}"""), request))
        assertFalse(QuoteUpdates.isFor(payload("""{"serviceRequestId":42}"""), request))
        assertFalse(QuoteUpdates.isFor(payload("""{"serviceRequestId":""}"""), ""))
    }
}
