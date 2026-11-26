package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.formFields
import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Refresh token rotation: an identity provider may accept each refresh token only once, so every
 * refresh must send the newest one, and requests that need a token at the same time must not each
 * refresh with the same old one.
 */
class SessionTokensTest {
    private val server = MockWebServer()
    private val clock = TestClock(Instant.parse("2026-11-25T12:00:00Z"))
    private val savedTokens = CopyOnWriteArrayList<Tokens>()
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
    fun `each refresh sends the refresh token the previous one issued, saved before it is sent`() {
        // For each refresh: the refresh token it sent, and the refresh tokens saved by then.
        val refreshes = CopyOnWriteArrayList<Pair<String?, List<String?>>>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    refreshes += request.formFields()["refresh_token"] to savedTokens.map { it.refreshToken }
                    val issued = refreshes.size + 1
                    return jsonResponse(tokenResponse("test-access-$issued", "test-refresh-$issued"))
                }
            }
        val tokens =
            sessionTokens(testTokens(accessToken = "test-access-1", refreshToken = "test-refresh-1", accessTokenExpiresAt = clock.now))

        assertEquals("test-access-2", tokens.accessToken())
        // The new access token lasts 300 seconds.
        clock.now += Duration.ofMinutes(10)
        assertEquals("test-access-3", tokens.accessToken())

        assertEquals(
            listOf(
                "test-refresh-1" to emptyList(),
                "test-refresh-2" to listOf("test-refresh-2"),
            ),
            refreshes,
        )
    }

    @Test
    fun `requests that need a new token at the same time share one refresh`() {
        // The answer is slow, so the other requests ask for a token while the refresh is on its way.
        server.enqueue(
            jsonResponse(tokenResponse("test-access-2", "test-refresh-2"))
                .newBuilder()
                .headersDelay(300, TimeUnit.MILLISECONDS)
                .build(),
        )
        val tokens = sessionTokens(testTokens(accessTokenExpiresAt = clock.now))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(REQUESTS)

        val accessTokens =
            try {
                val results =
                    List(REQUESTS) {
                        executor.submit(
                            Callable {
                                start.await()
                                tokens.accessToken()
                            },
                        )
                    }
                start.countDown()
                results.map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertEquals(List(REQUESTS) { "test-access-2" }, accessTokens)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("test-refresh-2"), savedTokens.map { it.refreshToken })
    }

    private fun sessionTokens(initial: Tokens) =
        SessionTokens(
            server = configuration,
            initialTokens = initial,
            tokenClient = TokenClient(newHttpClient(), clock),
            clock = clock,
            onRefreshed = { savedTokens += it },
            onEnded = { error("The session should not end") },
        )

    private fun tokenResponse(
        accessToken: String,
        refreshToken: String,
    ) = """{"access_token":"$accessToken","token_type":"Bearer","expires_in":300,"refresh_token":"$refreshToken"}"""

    private companion object {
        const val REQUESTS = 8
    }
}
