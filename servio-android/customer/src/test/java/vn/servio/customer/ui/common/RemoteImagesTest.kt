package vn.servio.customer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteImagesTest {

    private val base = "http://10.0.2.2:5080/"

    @Test
    fun relativeUrlsAreResolvedAgainstTheApiRoot() {
        assertEquals("http://10.0.2.2:5080/uploads/2026/11/a.jpg", RemoteImages.absolute("/uploads/2026/11/a.jpg", base))
        assertEquals("http://10.0.2.2:5080/uploads/a.jpg", RemoteImages.absolute("uploads/a.jpg", base))
        assertEquals("http://10.0.2.2:5080/uploads/a.jpg", RemoteImages.absolute("/uploads/a.jpg", "http://10.0.2.2:5080"))
    }

    @Test
    fun absoluteUrlsAreKept() {
        assertEquals("https://cdn.example.com/a.jpg", RemoteImages.absolute("https://cdn.example.com/a.jpg", base))
    }

    @Test
    fun onlyTheApiHostGetsTheClientWithTheToken() {
        assertTrue(RemoteImages.isApiHost("http://10.0.2.2:5080/uploads/a.jpg", base))
        assertFalse(RemoteImages.isApiHost("https://cdn.example.com/a.jpg", base))
        // Same host, other port or scheme: not the API.
        assertFalse(RemoteImages.isApiHost("http://10.0.2.2:8080/a.jpg", base))
        assertFalse(RemoteImages.isApiHost("https://10.0.2.2:5080/a.jpg", base))
        // A host that only starts like ours.
        assertFalse(RemoteImages.isApiHost("http://10.0.2.2.evil.test:5080/a.jpg", base))
        assertFalse(RemoteImages.isApiHost("not a url", base))
    }
}
