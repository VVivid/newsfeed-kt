package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.ResponseTooLargeException
import dev.vvivid.newsfeed.net.ServerJson
import dev.vvivid.newsfeed.net.bodyText
import dev.vvivid.newsfeed.net.hostWithPort
import dev.vvivid.newsfeed.net.send
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Why [ServerDiscovery] could not prepare sign-in for a server. */
enum class DiscoveryProblem {
    /** Nothing answered: the name does not resolve, there is no connection, or no answer in time. */
    Unreachable,

    /** The HTTPS connection failed, most often because the phone does not trust the certificate. */
    SecureConnectionFailed,

    /** Something answered, but not as an AgentNewsfeed server whose API accepts app sign-in. */
    NotNewsfeedServer,

    /** The server or its identity provider reported an internal error (HTTP 5xx). */
    ServerError,

    /** The server's sign-in setup is not one the app can use (see [ServerDiscovery]). */
    UnsupportedSignIn,
}

/** Discovery failed with [problem] at [host]: the server's host, or its identity provider's. */
class DiscoveryException(
    val problem: DiscoveryProblem,
    val host: String,
    cause: Throwable? = null,
) : Exception("Server discovery failed at $host: $problem", cause)

/**
 * Finds out how to sign in to an AgentNewsfeed server from its origin alone, with two public
 * requests that carry no credentials:
 * 1. The server's protected resource metadata for its API (RFC 9728) at
 *    `/.well-known/oauth-protected-resource/api/v1`. It must name exactly the API's resource
 *    (the origin + `/api/v1`), exactly one HTTPS issuer, both newsfeed scopes and, if it lists bearer
 *    methods, the Authorization header.
 * 2. That issuer's OpenID Connect discovery document. Its `issuer` must equal the one the server
 *    named, its authorization and token endpoints must be HTTPS, and it must offer PKCE with S256.
 *
 * Anything else fails with a [DiscoveryException] that says what went wrong, instead of a guess.
 */
class ServerDiscovery(
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Discovers the server at [origin], a normalized `https` origin from onboarding. */
    suspend fun discover(origin: String): ServerConfiguration {
        val originUrl = origin.toHttpUrl()
        val serverHost = originUrl.hostWithPort
        val apiResource = origin + API_PATH

        val metadataUrl = originUrl.newBuilder().addPathSegments(".well-known/oauth-protected-resource$API_PATH").build()
        val metadata = fetchJson(metadataUrl, serverHost, ProtectedResourceMetadata.serializer(), DiscoveryProblem.NotNewsfeedServer)
        // The metadata of another resource, for example after a proxy rewrote the path, is not this API's.
        if (metadata.resource != apiResource) fail(DiscoveryProblem.NotNewsfeedServer, serverHost)
        val issuer = metadata.authorizationServers.singleOrNull() ?: fail(DiscoveryProblem.UnsupportedSignIn, serverHost)
        // An issuer has no query (OpenID Connect Discovery 1.0, section 3); endpoints may have one
        // (RFC 6749, section 3.1), so httpsUrlOrNull allows it and this check is the issuer's alone.
        val issuerUrl = httpsUrlOrNull(issuer)?.takeIf { it.query == null } ?: fail(DiscoveryProblem.UnsupportedSignIn, serverHost)
        if (!metadata.scopesSupported.containsAll(listOf(NewsfeedScopes.READ, NewsfeedScopes.ARTICLES_WRITE))) {
            fail(DiscoveryProblem.UnsupportedSignIn, serverHost)
        }
        if (metadata.bearerMethodsSupported?.contains("header") == false) fail(DiscoveryProblem.UnsupportedSignIn, serverHost)

        val issuerHost = issuerUrl.hostWithPort
        // OpenID Connect Discovery 1.0, section 4: the issuer without a trailing slash, then the path.
        val configurationUrl = (issuer.trimEnd('/') + "/.well-known/openid-configuration").toHttpUrl()
        val openId = fetchJson(configurationUrl, issuerHost, OpenIdConfiguration.serializer(), DiscoveryProblem.UnsupportedSignIn)
        val isUsable =
            openId.issuer == issuer &&
                httpsUrlOrNull(openId.authorizationEndpoint) != null &&
                httpsUrlOrNull(openId.tokenEndpoint) != null &&
                PKCE_METHOD in openId.codeChallengeMethodsSupported &&
                openId.responseTypesSupported?.contains("code") != false
        if (!isUsable) fail(DiscoveryProblem.UnsupportedSignIn, issuerHost)

        return ServerConfiguration(
            origin = origin,
            apiResource = apiResource,
            issuer = issuer,
            authorizationEndpoint = openId.authorizationEndpoint,
            tokenEndpoint = openId.tokenEndpoint,
            // Optional: without a usable one, signing out only forgets the tokens on the phone.
            revocationEndpoint = openId.revocationEndpoint?.takeIf { httpsUrlOrNull(it) != null },
        )
    }

    private suspend fun <T> fetchJson(
        url: HttpUrl,
        host: String,
        deserializer: DeserializationStrategy<T>,
        problemIfInvalid: DiscoveryProblem,
    ): T {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
        return try {
            httpClient.send(request, ioDispatcher) { response ->
                when {
                    response.code == 200 -> Unit

                    response.code >= 500 -> fail(DiscoveryProblem.ServerError, host)

                    // Not found, a redirect, or any other answer: this is not the expected document.
                    else -> fail(problemIfInvalid, host)
                }
                try {
                    ServerJson.decodeFromString(deserializer, response.bodyText(MAX_DOCUMENT_BYTES))
                } catch (e: IllegalArgumentException) {
                    // SerializationException, for malformed JSON or a missing or mistyped field, is one.
                    fail(problemIfInvalid, host, e)
                }
            }
        } catch (e: ResponseTooLargeException) {
            fail(problemIfInvalid, host, e)
        } catch (e: IOException) {
            fail(if (e.isSecureConnectionFailure()) DiscoveryProblem.SecureConnectionFailed else DiscoveryProblem.Unreachable, host, e)
        }
    }

    /** What the server says about its API. */
    @Serializable
    private class ProtectedResourceMetadata(
        val resource: String,
        @SerialName("authorization_servers") val authorizationServers: List<String>,
        @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
        @SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String>? = null,
    )

    /** The parts of the issuer's OpenID Connect discovery document the app uses. */
    @Serializable
    private class OpenIdConfiguration(
        val issuer: String,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String,
        @SerialName("token_endpoint") val tokenEndpoint: String,
        @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
        @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = emptyList(),
        @SerialName("response_types_supported") val responseTypesSupported: List<String>? = null,
    )

    private companion object {
        const val API_PATH = "/api/v1"
        const val PKCE_METHOD = "S256"

        /** Far above any real document; Keycloak's discovery document is a few kilobytes. */
        const val MAX_DOCUMENT_BYTES = 64L * 1024
    }
}

private fun fail(
    problem: DiscoveryProblem,
    host: String,
    cause: Throwable? = null,
): Nothing = throw DiscoveryException(problem, host, cause)

/**
 * [url] if it is an absolute `https` URL without user info or fragment, which is what OAuth requires
 * of issuers and endpoints.
 */
private fun httpsUrlOrNull(url: String): HttpUrl? =
    url
        .takeIf { it.startsWith("https://") }
        ?.toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }

/** Whether this failure, or one that caused it, is TLS: a handshake or certificate problem. */
private fun Throwable.isSecureConnectionFailure(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSES_CHECKED).any { it is SSLException || it is CertificateException }

private const val MAX_CAUSES_CHECKED = 8
