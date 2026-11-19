package vn.servio.customer.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.data.remote.ServerConfigApi
import java.io.IOException

class ServerConfigRepositoryTest {

    /** #107 answering each config of [answers] in turn; null = no network. */
    private class Api(vararg answers: ServerConfig?) : ServerConfigApi {
        private val queue = ArrayDeque(answers.toList())
        var calls = 0

        override suspend fun getServerConfig(): ApiResponse<ServerConfig> {
            calls++
            val next = queue.removeFirst() ?: throw IOException("offline")
            return ApiResponse(success = true, data = next)
        }
    }

    @Test
    fun `asks every time and keeps the last answer of the session`() = runTest {
        val first = serverConfig()
        val changed = serverConfig(autoConfirmHours = 12)
        val api = Api(first, changed)
        val repository = RemoteServerConfigRepository(api)
        assertNull(repository.latest)
        assertEquals(ApiResult.Success(first), repository.getConfig())
        assertEquals(ApiResult.Success(changed), repository.getConfig())
        assertEquals(2, api.calls)
        assertEquals(changed, repository.latest)
    }

    @Test
    fun `a failure answers with the session's last config`() = runTest {
        val repository = RemoteServerConfigRepository(Api(serverConfig(), null))
        repository.getConfig()
        assertEquals(ApiResult.Success(serverConfig()), repository.getConfig())
    }

    @Test
    fun `a failure with nothing known yet is a failure`() = runTest {
        val repository = RemoteServerConfigRepository(Api(null))
        assertTrue(repository.getConfig() is ApiResult.Failure)
        assertNull(repository.latest)
    }
}
