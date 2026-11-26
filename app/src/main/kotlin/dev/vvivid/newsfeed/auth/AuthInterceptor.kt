package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.hasOrigin
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Adds the session's access token to requests for the signed-in server, and only to those.
 *
 * - A request goes out with `Authorization: Bearer` only if its URL has exactly the server's origin
 *   (scheme, host and port). Every other request, to an article's site for example, goes without it.
 *   The client never follows redirects (see `newHttpClient`), so a token cannot travel on to
 *   another address either.
 * - Before each request, [SessionTokens.accessToken] refreshes a token that is about to expire.
 * - When the server answers HTTP 401 anyway, the request is retried once with a newer token: the
 *   one another request refreshed meanwhile, or else one refreshed now
 *   ([SessionTokens.accessTokenReplacing]). If the server rejects that token too, the session ends
 *   ([SessionTokens.notAccepted]) and the request fails with [SessionEndedException].
 * - Every failure leaves as an [IOException]; a runtime exception, from a bug or from an identity
 *   provider answer the app cannot use, is wrapped in one. OkHttp reports an IOException from an
 *   interceptor as a failed request, but rethrows anything else on its own thread, which would end
 *   the app.
 */
class AuthInterceptor(
    private val serverOrigin: HttpUrl,
    private val tokens: SessionTokens,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.hasOrigin(serverOrigin)) return chain.proceed(request)
        return try {
            proceedAuthorized(chain, request)
        } catch (e: RuntimeException) {
            // A bug, or an answer from the identity provider that the app cannot use.
            throw IOException("The request could not be authorized", e)
        }
    }

    private fun proceedAuthorized(
        chain: Interceptor.Chain,
        request: Request,
    ): Response {
        val token = tokens.accessToken()
        val response = chain.proceed(request.withAccessToken(token))
        if (response.code != HTTP_UNAUTHORIZED) return response
        response.close()

        val retried = chain.proceed(request.withAccessToken(tokens.accessTokenReplacing(token)))
        if (retried.code != HTTP_UNAUTHORIZED) return retried
        retried.close()
        tokens.notAccepted()
        throw SessionEndedException()
    }

    private fun Request.withAccessToken(token: String): Request = newBuilder().header("Authorization", "Bearer $token").build()

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
