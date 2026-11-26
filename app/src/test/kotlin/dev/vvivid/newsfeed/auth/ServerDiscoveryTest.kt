package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.clientRoutedTo
import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
import dev.vvivid.newsfeed.net.nextRequest
import dev.vvivid.newsfeed.net.originalUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ServerDiscoveryTest {
    private val server = MockWebServer()
    private val origin = "https://news.example.com"
    private val issuer = "https://auth.example.com/realms/test"

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a newsfeed server and its issuer give the sign-in configuration`() =
        runTest {
            server.enqueue(jsonResponse(metadata()))
            server.enqueue(jsonResponse(openIdConfiguration()))

            val configuration = discover()

            assertEquals(
                ServerConfiguration(
                    origin = origin,
                    apiResource = "$origin/api/v1",
                    issuer = issuer,
                    authorizationEndpoint = "$issuer/protocol/openid-connect/auth",
                    tokenEndpoint = "$issuer/protocol/openid-connect/token",
                    revocationEndpoint = "$issuer/protocol/openid-connect/revoke",
                ),
                configuration,
            )
            assertEquals("auth.example.com", configuration.signInHost)
            val metadataRequest = server.nextRequest()
            assertEquals("$origin/.well-known/oauth-protected-resource/api/v1", metadataRequest.originalUrl)
            assertNull(metadataRequest.headers["Authorization"])
            assertEquals("$issuer/.well-known/openid-configuration", server.nextRequest().originalUrl)
        }

    @Test
    fun `the metadata of another resource is not this server's API`() =
        runTest {
            server.enqueue(jsonResponse(metadata(resource = "$origin/mcp")))

            assertProblem(DiscoveryProblem.NotNewsfeedServer, "news.example.com")
        }

    @Test
    fun `a server without app sign-in is not usable`() =
        runTest {
            server.enqueue(MockResponse(code = 404, body = "Not found"))

            assertProblem(DiscoveryProblem.NotNewsfeedServer, "news.example.com")
        }

    @Test
    fun `an answer that is not the metadata is not usable`() =
        runTest {
            server.enqueue(MockResponse(code = 200, headers = headersOf("Content-Type", "text/html"), body = "<html>Welcome</html>"))

            assertProblem(DiscoveryProblem.NotNewsfeedServer, "news.example.com")
        }

    @Test
    fun `a redirect is not followed`() =
        runTest {
            server.enqueue(MockResponse(code = 302, headers = headersOf("Location", "https://elsewhere.example.org/"), body = ""))

            assertProblem(DiscoveryProblem.NotNewsfeedServer, "news.example.com")
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `an http issuer is not supported`() =
        runTest {
            server.enqueue(jsonResponse(metadata(issuers = """["http://auth.example.com/realms/test"]""")))

            assertProblem(DiscoveryProblem.UnsupportedSignIn, "news.example.com")
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `more than one issuer is not supported`() =
        runTest {
            server.enqueue(jsonResponse(metadata(issuers = """["$issuer","https://auth.example.org/realms/test"]""")))

            assertProblem(DiscoveryProblem.UnsupportedSignIn, "news.example.com")
        }

    @Test
    fun `a server that does not offer both newsfeed scopes is not supported`() =
        runTest {
            server.enqueue(jsonResponse(metadata(scopes = """["newsfeed:read"]""")))

            assertProblem(DiscoveryProblem.UnsupportedSignIn, "news.example.com")
        }

    @Test
    fun `an issuer whose discovery names another issuer is not supported`() =
        runTest {
            server.enqueue(jsonResponse(metadata()))
            server.enqueue(jsonResponse(openIdConfiguration(issuerClaim = "https://auth.example.com/realms/other")))

            assertProblem(DiscoveryProblem.UnsupportedSignIn, "auth.example.com")
        }

    @Test
    fun `an issuer without PKCE S256 is not supported`() =
        runTest {
            server.enqueue(jsonResponse(metadata()))
            server.enqueue(jsonResponse(openIdConfiguration(challengeMethods = """["plain"]""")))

            assertProblem(DiscoveryProblem.UnsupportedSignIn, "auth.example.com")
        }

    @Test
    fun `an http token endpoint is not supported`() =
        runTest {
            server.enqueue(jsonResponse(metadata()))
            server.enqueue(jsonResponse(openIdConfiguration(tokenEndpoint = "http://auth.example.com/realms/test/token")))

            assertProblem(DiscoveryProblem.UnsupportedSignIn, "auth.example.com")
        }

    @Test
    fun `an unusable revocation endpoint is left out`() =
        runTest {
            server.enqueue(jsonResponse(metadata()))
            server.enqueue(jsonResponse(openIdConfiguration(revocationEndpoint = "http://auth.example.com/revoke")))

            assertNull(discover().revocationEndpoint)
        }

    @Test
    fun `a server error is reported as one`() =
        runTest {
            server.enqueue(MockResponse(code = 503, body = "Unavailable"))

            assertProblem(DiscoveryProblem.ServerError, "news.example.com")
        }

    @Test
    fun `a host that cannot be found is unreachable`() =
        runTest {
            val client = newHttpClient().newBuilder().addInterceptor { throw UnknownHostException("news.example.com") }.build()

            assertProblem(DiscoveryProblem.Unreachable, "news.example.com", client)
        }

    @Test
    fun `an untrusted certificate is a secure connection failure`() =
        runTest {
            val client = newHttpClient().newBuilder().addInterceptor { throw SSLHandshakeException("untrusted") }.build()

            assertProblem(DiscoveryProblem.SecureConnectionFailed, "news.example.com", client)
        }

    private suspend fun discover(client: OkHttpClient = clientRoutedTo(server)): ServerConfiguration =
        ServerDiscovery(client, Dispatchers.IO).discover(origin)

    private suspend fun assertProblem(
        problem: DiscoveryProblem,
        host: String,
        client: OkHttpClient = clientRoutedTo(server),
    ) {
        try {
            discover(client)
            fail("Discovery should have failed with $problem")
        } catch (e: DiscoveryException) {
            assertEquals(problem, e.problem)
            assertEquals(host, e.host)
        }
    }

    private fun metadata(
        resource: String = "$origin/api/v1",
        issuers: String = """["$issuer"]""",
        scopes: String = """["newsfeed:read","newsfeed:articles:write"]""",
    ) = """{"resource":"$resource","authorization_servers":$issuers,"scopes_supported":$scopes,"bearer_methods_supported":["header"]}"""

    private fun openIdConfiguration(
        issuerClaim: String = issuer,
        challengeMethods: String = """["plain","S256"]""",
        tokenEndpoint: String = "$issuer/protocol/openid-connect/token",
        revocationEndpoint: String = "$issuer/protocol/openid-connect/revoke",
    ) = """
        {
          "issuer": "$issuerClaim",
          "authorization_endpoint": "$issuer/protocol/openid-connect/auth",
          "token_endpoint": "$tokenEndpoint",
          "revocation_endpoint": "$revocationEndpoint",
          "jwks_uri": "$issuer/protocol/openid-connect/certs",
          "response_types_supported": ["code", "none"],
          "code_challenge_methods_supported": $challengeMethods,
          "claims_supported": ["sub", "iss"],
          "mtls_endpoint_aliases": {"token_endpoint": "$issuer/mtls/token"}
        }
        """.trimIndent()
}
