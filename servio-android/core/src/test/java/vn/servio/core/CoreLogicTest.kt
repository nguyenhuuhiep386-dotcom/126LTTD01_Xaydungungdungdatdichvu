package vn.servio.core

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.ApiResult
import vn.servio.core.network.ErrorMessages
import vn.servio.core.network.apiCall
import vn.servio.core.ui.auth.PhoneInputViewModel
import vn.servio.core.utils.Money
import java.io.IOException

/** Reference unit tests for pure logic in :core. Run: ./gradlew :core:testDebugUnitTest */
class CoreLogicTest {

    @Test
    fun `money is formatted as whole VND with dot separators`() {
        assertEquals("450.000 đ", Money.format(450_000))
        assertEquals("200.000 đ – 400.000 đ", Money.range(200_000, 400_000))
        assertEquals("≤ 300.000 đ", Money.range(null, 300_000))
        assertNull(Money.range(null, null))
    }

    @Test
    fun `phone must have 10 digits starting with 0`() {
        assertTrue(PhoneInputViewModel.isValidPhone("0901234567"))
        assertFalse(PhoneInputViewModel.isValidPhone("901234567"))
        assertFalse(PhoneInputViewModel.isValidPhone("09012345678"))
    }

    @Test
    fun `apiCall unwraps a successful envelope`() = runTest {
        val result = apiCall { ApiResponse(success = true, data = 42) }
        assertEquals(ApiResult.Success(42), result)
    }

    @Test
    fun `apiCall maps an offline error to the network message`() = runTest {
        val result = apiCall<Int> { throw IOException("offline") }
        assertEquals(ErrorMessages.NETWORK_ERROR, (result as ApiResult.Failure).code)
    }

    @Test
    fun `apiCall reads the error code from the server envelope`() = runTest {
        val body = """{"success":false,"message":"x","errors":[{"code":"OTP_INVALID","field":"code","message":"Mã OTP không đúng"}]}"""
        val error = HttpException(Response.error<Any>(400, body.toResponseBody()))

        val result = apiCall<Int> { throw error } as ApiResult.Failure

        assertEquals("OTP_INVALID", result.code)
        assertEquals("code", result.field)
        assertEquals(ErrorMessages.forCode("OTP_INVALID"), result.message)
    }
}
