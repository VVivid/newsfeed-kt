package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.hostWithPort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Everything the app needs to sign in to one AgentNewsfeed server and use its API, as
 * [ServerDiscovery] found and checked it. Nothing here is secret: it is saved in plain JSON next to
 * the encrypted tokens and kept in onboarding's saved state while the sign-in page is open.
 *
 * @property origin the server, such as `https://news.example.com`.
 * @property apiResource the API's resource identifier, `origin` + `/api/v1`. Sign-in sends it as the
 *   `resource` of the authorization, token and refresh requests, so that the access token is issued
 *   for this API alone.
 * @property issuer the OpenID Connect issuer the server trusts, exactly as the server names it.
 * @property revocationEndpoint where the issuer revokes tokens (RFC 7009), if it offers that.
 */
@Serializable
data class ServerConfiguration(
    val origin: String,
    @SerialName("api_resource") val apiResource: String,
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
) {
    /** Where the user signs in, such as `auth.example.com`: shown before signing in and in Settings. */
    val signInHost: String
        get() = issuer.toHttpUrl().hostWithPort
}
