package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.RouteToMockServer
import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
import dev.vvivid.newsfeed.net.nextRequest
import dev.vvivid.newsfeed.net.originalUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * [AuthInterceptor] with real [SessionTokens]. API requests use the server's https address and are
 * routed to the test server after the interceptor has seen them; the token endpoint is the test
 * server itself.
 */
class AuthInterceptorTest {
    private val server = MockWebServer()
    private val clock = TestClock(Instant.parse("2026-11-25T12:00:00Z"))
    private val refreshedTokens = mutableListOf<Tokens>()
    private val endReasons = mutableListOf<SessionEndReason>()
    private lateinit var configuration: ServerConfiguration

    @Before
    fun startServer() {
        server.start()
        configuration = testServer(issuerOrigin = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `the access token goes to the server's origin and nowhere else`() {
        val client = client(sessionTokens())
        val urls =
            listOf(
                "https://news.example.com/api/v1/articles",
                // The same host with another port or scheme, another host, and a subdomain.
                "https://news.example.com:8443/api/v1/articles",
                "http://news.example.com/api/v1/articles",
                "https://other.example.org/api/v1/articles",
                "https://cdn.news.example.com/thumbnails/1",
            )

        for (url in urls) {
            server.enqueue(MockResponse(code = 200, body = "{}"))
            client.get(url)
        }

        val authorizations = List(urls.size) { server.nextRequest().headers["Authorization"] }
        assertEquals(listOf("Bearer test-access-1", null, null, null, null), authorizations)
    }

    @Test
    fun `an expiring access token is refreshed before the request`() {
        val tokens = sessionTokens(testTokens(accessTokenExpiresAt = clock.now.plusSeconds(10)))
        server.enqueue(jsonResponse(tokenResponse("test-access-2", "test-refresh-2")))
        server.enqueue(MockResponse(code = 200, body = "{}"))

        client(tokens).get(ARTICLES)

        assertEquals("/realms/test/protocol/openid-connect/token", server.nextRequest().url.encodedPath)
        assertEquals("Bearer test-access-2", server.nextRequest().headers["Authorization"])
        assertEquals(listOf("test-refresh-2"), refreshedTokens.map { it.refreshToken })
    }

    @Test
    fun `a rejected access token is refreshed once and the request tried again`() {
        server.enqueue(MockResponse(code = 401, body = "Unauthorized"))
        server.enqueue(jsonResponse(tokenResponse("test-access-2", refreshToken = null)))
        server.enqueue(MockResponse(code = 200, body = "{}"))

        val code = client(sessionTokens()).get(ARTICLES)

        assertEquals(200, code)
        assertEquals("Bearer test-access-1", server.nextRequest().headers["Authorization"])
        server.nextRequest()
        val retry = server.nextRequest()
        assertEquals("https://news.example.com/api/v1/articles", retry.originalUrl)
        assertEquals("Bearer test-access-2", retry.headers["Authorization"])
        // The provider issued no new refresh token, so the previous one stays.
        assertEquals(listOf("test-refresh-1"), refreshedTokens.map { it.refreshToken })
        assertTrue(endReasons.isEmpty())
    }

    @Test
    fun `a token the server rejects again after a refresh ends the session`() {
        val client = client(sessionTokens())
        server.enqueue(MockResponse(code = 401, body = "Unauthorized"))
        server.enqueue(jsonResponse(tokenResponse("test-access-2", "test-refresh-2")))
        server.enqueue(MockResponse(code = 401, body = "Unauthorized"))

        assertThrows(SessionEndedException::class.java) { client.get(ARTICLES) }

        assertEquals(listOf(SessionEndReason.NotAccepted), endReasons)
        assertThrows(SessionEndedException::class.java) { client.get(ARTICLES) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an absurd token lifetime from the identity provider does not break the request`() {
        // Regression: an expires_in near Long.MAX_VALUE used to overflow on an OkHttp thread.
        val tokens = sessionTokens(testTokens(accessTokenExpiresAt = clock.now))
        server.enqueue(jsonResponse("""{"access_token":"test-access-2","token_type":"Bearer","expires_in":9223372036854775807}"""))
        server.enqueue(MockResponse(code = 200, body = "{}"))

        assertEquals(200, client(tokens).get(ARTICLES))

        assertEquals("/realms/test/protocol/openid-connect/token", server.nextRequest().url.encodedPath)
        assertEquals("Bearer test-access-2", server.nextRequest().headers["Authorization"])
        assertTrue(endReasons.isEmpty())
    }

    @Test
    fun `an unexpected failure while authorizing fails the request instead of the app`() {
        // OkHttp rethrows anything but an IOException from an interceptor on its own thread, which
        // ends the app, so a failure such as this one must reach the caller as an IOException.
        val tokens =
            SessionTokens(
                server = configuration,
                initialTokens = testTokens(accessTokenExpiresAt = clock.now),
                tokenClient = TokenClient(newHttpClient(), clock),
                clock = clock,
                onRefreshed = { throw IllegalStateException("Synthetic failure while saving") },
                onEnded = { endReasons += it },
            )
        server.enqueue(jsonResponse(tokenResponse("test-access-2", "test-refresh-2")))

        val failure = assertThrows(IOException::class.java) { client(tokens).get(ARTICLES) }

        assertTrue(failure.cause is IllegalStateException)
        // Only the refresh was sent; the API request was not.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refresh the identity provider refuses ends the session as expired`() {
        val client = client(sessionTokens())
        server.enqueue(MockResponse(code = 401, body = "Unauthorized"))
        server.enqueue(jsonResponse("""{"error":"invalid_grant"}""", code = 400))

        assertThrows(SessionEndedException::class.java) { client.get(ARTICLES) }

        assertEquals(listOf(SessionEndReason.Expired), endReasons)
        assertTrue(refreshedTokens.isEmpty())
    }

    @Test
    fun `a refresh that does not get through fails the request but keeps the session`() {
        val client = client(sessionTokens(testTokens(accessTokenExpiresAt = clock.now)))
        server.enqueue(MockResponse(code = 503, body = "Unavailable"))

        val failure = assertThrows(IOException::class.java) { client.get(ARTICLES) }

        assertFalse(failure is SessionEndedException)
        assertTrue(endReasons.isEmpty())
        server.enqueue(jsonResponse(tokenResponse("test-access-2", "test-refresh-2")))
        server.enqueue(MockResponse(code = 200, body = "{}"))
        assertEquals(200, client.get(ARTICLES))
    }

    @Test
    fun `a token another request already refreshed is used without refreshing again`() {
        val tokens = sessionTokens()
        server.enqueue(jsonResponse(tokenResponse("test-access-2", "test-refresh-2")))

        assertEquals("test-access-2", tokens.accessTokenReplacing("test-access-1"))
        assertEquals("test-access-2", tokens.accessTokenReplacing("test-access-1"))

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect comes back to the caller instead of being followed with the token`() {
        server.enqueue(MockResponse(code = 302, headers = headersOf("Location", "https://other.example.org/collect"), body = ""))

        assertEquals(302, client(sessionTokens()).get(ARTICLES))

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `after sign-out no request gets a token`() {
        val tokens = sessionTokens()

        assertEquals("test-refresh-1", tokens.close())

        assertThrows(SessionEndedException::class.java) { client(tokens).get(ARTICLES) }
        assertEquals(0, server.requestCount)
        assertNull(tokens.close())
        assertTrue(endReasons.isEmpty())
    }

    private fun sessionTokens(initial: Tokens = testTokens()) =
        SessionTokens(
            server = configuration,
            initialTokens = initial,
            tokenClient = TokenClient(newHttpClient(), clock),
            clock = clock,
            onRefreshed = { refreshedTokens += it },
            onEnded = { endReasons += it },
        )

    private fun client(tokens: SessionTokens): OkHttpClient =
        newHttpClient()
            .newBuilder()
            .addInterceptor(AuthInterceptor(configuration.origin.toHttpUrl(), tokens))
            .addInterceptor(RouteToMockServer(server))
            .build()

    /** Sends a GET and returns the status code. */
    private fun OkHttpClient.get(url: String): Int = newCall(Request.Builder().url(url).build()).execute().use { it.code }

    private fun tokenResponse(
        accessToken: String,
        refreshToken: String?,
    ): String {
        val refreshField = refreshToken?.let { ""","refresh_token":"$it"""" }.orEmpty()
        return """{"access_token":"$accessToken","token_type":"Bearer","expires_in":300$refreshField}"""
    }

    private companion object {
        const val ARTICLES = "https://news.example.com/api/v1/articles"
    }
}
