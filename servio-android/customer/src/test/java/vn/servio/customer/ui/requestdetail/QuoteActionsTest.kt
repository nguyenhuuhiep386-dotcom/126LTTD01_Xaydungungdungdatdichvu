package vn.servio.customer.ui.requestdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteActionsTest {

    @Test
    fun `pending quote of an open request shows both buttons`() {
        assertEquals(QuoteActions(chat = true, select = true), QuoteActions.of(selectable = true, conversationId = "c1"))
    }

    @Test
    fun `quote that cannot be chosen keeps only Nhan tin`() {
        val actions = QuoteActions.of(selectable = false, conversationId = "c1")
        assertTrue(actions.chat)
        assertFalse(actions.select)
        assertTrue(actions.row)
    }

    @Test
    fun `no conversation means no Nhan tin`() {
        assertEquals(QuoteActions(chat = false, select = true), QuoteActions.of(selectable = true, conversationId = null))
    }

    @Test
    fun `nothing to show hides the row`() {
        assertFalse(QuoteActions.of(selectable = false, conversationId = null).row)
    }
}
