package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.ServerJson
import dev.vvivid.newsfeed.net.bodyText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Talks to the identity provider's token endpoint as the app's public client: no client secret,
 * just [NativeClient.ID] in the request. Every token request names the server's API as its
 * `resource` (RFC 8707), so the access token is issued for that API alone, which the server requires.
 *
 * The methods block on the network: call them off the main thread. They throw
 * [TokenRequestRejectedException] when the provider refuses the request and another [IOException]
 * when it could not be asked or did not answer usefully.
 */
class TokenClient(
    private val httpClient: OkHttpClient,
    private val clock: Clock,
) {
    /** Exchanges the code from a browser sign-in, proving with [codeVerifier] that the app asked for it (PKCE). */
    fun exchangeCode(
        server: ServerConfiguration,
        code: String,
        codeVerifier: String,
    ): TokenGrant =
        requestTokens(
            server,
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", NativeClient.REDIRECT_URI)
                .add("code_verifier", codeVerifier)
                .add("client_id", NativeClient.ID)
                .add("resource", server.apiResource)
                .build(),
        )

    /** Gets a new access token, and usually a new refresh token, with [refreshToken]. */
    fun refresh(
        server: ServerConfiguration,
        refreshToken: String,
    ): TokenGrant =
        requestTokens(
            server,
            FormBody
                .Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", NativeClient.ID)
                .add("resource", server.apiResource)
                .build(),
        )

    /**
     * Asks the provider to revoke [refreshToken] (RFC 7009), ending the sign-in there too. Does
     * nothing when the provider offers no revocation. Callers treat this as best effort: a failure
     * leaves the token to expire on its own.
     */
    fun revoke(
        server: ServerConfiguration,
        refreshToken: String,
    ) {
        val endpoint = server.revocationEndpoint ?: return
        val form =
            FormBody
                .Builder()
                .add("token", refreshToken)
                .add("token_type_hint", "refresh_token")
                .add("client_id", NativeClient.ID)
                .build()
        httpClient
            .newCall(
                Request
                    .Builder()
                    .url(endpoint)
                    .post(form)
                    .build(),
            ).execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("Token revocation answered HTTP ${response.code}")
            }
    }

    private fun requestTokens(
        server: ServerConfiguration,
        form: FormBody,
    ): TokenGrant {
        val request =
            Request
                .Builder()
                .url(server.tokenEndpoint)
                .header("Accept", "application/json")
                .post(form)
                .build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> return readGrant(response.bodyText(MAX_RESPONSE_BYTES))

                // RFC 6749 5.2: the grant, the client or the request is not acceptable. Asking again
                // the same way cannot succeed.
                400, 401 -> throw TokenRequestRejectedException(response.code)

                else -> throw IOException("The token endpoint answered HTTP ${response.code}")
            }
        }
    }

    private fun readGrant(json: String): TokenGrant {
        val response =
            try {
                ServerJson.decodeFromString(TokenResponseJson.serializer(), json)
            } catch (e: IllegalArgumentException) {
                throw IOException("The token response is not valid JSON of the expected shape", e)
            }
        if (!response.tokenType.equals("Bearer", ignoreCase = true) || response.accessToken.isBlank()) {
            throw IOException("The token response has no bearer access token")
        }
        return TokenGrant(
            accessToken = response.accessToken,
            accessTokenExpiresAt = clock.instant() + accessTokenLifetime(response.expiresInSeconds),
            refreshToken = response.refreshToken?.ifBlank { null },
            idToken = response.idToken?.ifBlank { null },
            grantedScopes =
                response.scope
                    ?.split(' ')
                    ?.filter { it.isNotEmpty() }
                    ?.toSet(),
        )
    }

    /**
     * How long the access token lasts, from `expires_in`. Without a positive value it is assumed to be
     * short. A longer value than [MAX_ACCESS_TOKEN_SECONDS] is capped: refreshing early is harmless,
     * while an absurd value would overflow the expiry time.
     */
    private fun accessTokenLifetime(expiresInSeconds: Long?): Duration {
        val seconds = expiresInSeconds?.takeIf { it > 0 }?.coerceAtMost(MAX_ACCESS_TOKEN_SECONDS) ?: DEFAULT_ACCESS_TOKEN_SECONDS
        return Duration.ofSeconds(seconds)
    }

    /** A successful token response (RFC 6749 5.1). Not a data class, so no toString shows a token. */
    @Serializable
    private class TokenResponseJson(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String,
        @SerialName("expires_in") val expiresInSeconds: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        val scope: String? = null,
    )

    private companion object {
        /** Token responses are a few kilobytes. */
        const val MAX_RESPONSE_BYTES = 64L * 1024

        /** When the answer gives no lifetime, assume a short one; a 401 also triggers a refresh. */
        const val DEFAULT_ACCESS_TOKEN_SECONDS = 60L

        /** A year: far longer than any access token is meant to last. */
        const val MAX_ACCESS_TOKEN_SECONDS = 365L * 24 * 60 * 60
    }
}

/**
 * The tokens the identity provider issued.
 *
 * @property refreshToken null when the provider issued none; a refresh may also leave out a new one,
 *   and then the previous refresh token stays valid.
 * @property idToken the OpenID Connect ID token, which sign-in checks.
 * @property grantedScopes the permissions granted, or null when the answer does not list them, which
 *   means the requested ones were granted (RFC 6749 5.1).
 */
class TokenGrant(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String?,
    val idToken: String?,
    val grantedScopes: Set<String>?,
) {
    override fun toString(): String = "TokenGrant(tokens hidden, expires at $accessTokenExpiresAt)"
}

/**
 * The identity provider refused a token request with HTTP [status] (400 or 401). For a refresh this
 * means the sign-in has expired or was revoked.
 */
class TokenRequestRejectedException(
    val status: Int,
) : IOException("The identity provider refused the token request (HTTP $status)")
