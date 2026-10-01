package vn.servio.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import vn.servio.core.BuildConfig
import vn.servio.core.data.local.TokenStore
import vn.servio.core.data.model.RefreshRequest
import vn.servio.core.data.model.RefreshResult
import vn.servio.core.utils.AppConfig
import vn.servio.core.utils.SessionEvents
import java.io.IOException
import java.util.UUID

/** Adds the standard headers (spec 6.1) and the Bearer token to every request. */
class HeadersInterceptor(
    private val tokenStore: TokenStore,
    private val appConfig: AppConfig,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("X-Client-Platform", appConfig.platformHeader)
            .header("X-Client-Version", appConfig.versionName)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("Accept-Language", "vi-VN")
        tokenStore.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        return chain.proceed(builder.build())
    }
}

/**
 * On 401, exchanges the refresh token (#5) once and retries the request.
 * If the refresh token is rejected, clears the session and emits [SessionEvents.expired].
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
    private val json: Json,
) : Authenticator {

    // Plain client without this authenticator, so the refresh call cannot loop.
    private val refreshClient = OkHttpClient()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.endsWith("/auth/refresh") || responseCount(response) > 1) {
            return null
        }

        synchronized(this) {
            val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            val current = tokenStore.accessToken
            // Another request already refreshed the token: retry with the new one.
            if (current != null && current != failedToken) {
                return response.request.withToken(current)
            }

            val refreshToken = tokenStore.refreshToken ?: return expireSession()
            return when (val outcome = refresh(refreshToken)) {
                is RefreshOutcome.Refreshed -> {
                    tokenStore.save(outcome.tokens.accessToken, outcome.tokens.refreshToken)
                    response.request.withToken(outcome.tokens.accessToken)
                }
                RefreshOutcome.Rejected -> expireSession()
                // Network error or server down: keep the session, the screen shows its normal error and can retry.
                RefreshOutcome.Unavailable -> null
            }
        }
    }

    private fun expireSession(): Request? {
        tokenStore.clear()
        sessionEvents.notifyExpired()
        return null
    }

    private sealed interface RefreshOutcome {
        data class Refreshed(val tokens: RefreshResult) : RefreshOutcome
        /** Server answered 400/401: the refresh token is invalid, revoked or expired. */
        data object Rejected : RefreshOutcome
        data object Unavailable : RefreshOutcome
    }

    private fun refresh(refreshToken: String): RefreshOutcome = try {
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(BuildConfig.BASE_URL + "api/v1/auth/refresh").post(body).build()
        refreshClient.newCall(request).execute().use { response ->
            val tokens = response.body?.string()?.takeIf { response.isSuccessful }?.let {
                json.decodeFromString(ApiResponse.serializer(RefreshResult.serializer()), it).data
            }
            when {
                tokens != null -> RefreshOutcome.Refreshed(tokens)
                response.code == 400 || response.code == 401 -> RefreshOutcome.Rejected
                else -> RefreshOutcome.Unavailable
            }
        }
    } catch (e: IOException) {
        RefreshOutcome.Unavailable
    } catch (e: SerializationException) {
        RefreshOutcome.Unavailable
    }

    private fun Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
