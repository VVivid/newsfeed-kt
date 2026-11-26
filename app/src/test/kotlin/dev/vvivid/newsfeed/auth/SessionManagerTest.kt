package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.net.formFields
import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
import dev.vvivid.newsfeed.net.nextRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/**
 * The session's whole life: restore, sign-in, refresh, sign-out and an ending. The test server plays
 * both the newsfeed server and its identity provider, at plain-http test addresses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val clock = TestClock(Instant.parse("2026-11-25T12:00:00Z"))
    private lateinit var configuration: ServerConfiguration
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        server.start()
        val origin = server.url("/").toString().trimEnd('/')
        configuration = testServer(origin = origin, issuerOrigin = origin)
        store = SessionStore({ File(folder.root, "session.json") }, TestTokenCipher())
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a saved session is restored at start`() =
        runTest {
            store.saveSignedIn(configuration, testTokens())
            val manager = sessionManager()
            assertEquals(SessionState.Restoring, manager.state.value)

            manager.restore()

            assertEquals(configuration, (manager.state.value as SessionState.SignedIn).session.server)
        }

    @Test
    fun `without a saved session the app starts signed out, offering the last server`() =
        runTest {
            store.saveSignedOut(lastServerOrigin = configuration.origin)
            val manager = sessionManager()

            manager.restore()

            assertEquals(SessionState.SignedOut(lastServerOrigin = configuration.origin), manager.state.value)
        }

    @Test
    fun `signing in saves the session and starts it`() =
        runTest {
            val manager = restoredManager()

            manager.signIn(configuration, testTokens())

            assertTrue(manager.state.value is SessionState.SignedIn)
            assertTrue(store.read() is SessionStore.Saved.SignedIn)
        }

    @Test
    fun `signing out forgets the tokens, keeps the server and revokes the refresh token`() =
        runTest {
            val manager = restoredManager()
            manager.signIn(configuration, testTokens(refreshToken = "test-refresh-1"))
            server.enqueue(MockResponse(code = 200, body = ""))

            manager.signOut(forgetServer = false)
            assertEquals(SessionState.SignedOut(lastServerOrigin = configuration.origin), manager.state.value)
            advanceUntilIdle()

            assertEquals(SessionStore.Saved.SignedOut(configuration.origin), store.read())
            val revocation = server.nextRequest()
            assertEquals("/realms/test/protocol/openid-connect/revoke", revocation.url.encodedPath)
            assertEquals("test-refresh-1", revocation.formFields()["token"])
        }

    @Test
    fun `changing the server forgets the server too`() =
        runTest {
            val manager = restoredManager()
            manager.signIn(configuration.copy(revocationEndpoint = null), testTokens())

            manager.signOut(forgetServer = true)
            advanceUntilIdle()

            assertEquals(SessionState.SignedOut(lastServerOrigin = null), manager.state.value)
            assertEquals(SessionStore.Saved.SignedOut(lastServerOrigin = null), store.read())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `refreshed tokens are saved for the next start`() =
        runTest {
            val manager = restoredManager()
            manager.signIn(configuration, testTokens())
            val session = (manager.state.value as SessionState.SignedIn).session
            server.enqueue(MockResponse(code = 401, body = "Unauthorized"))
            server.enqueue(
                jsonResponse(
                    """{"access_token":"test-access-2","token_type":"Bearer","expires_in":300,"refresh_token":"test-refresh-2"}""",
                ),
            )
            server.enqueue(jsonResponse("""{"articles":null}"""))

            session.newsRepository.listArticles(Shelf.Active)

            val saved = store.read() as SessionStore.Saved.SignedIn
            assertEquals("test-access-2", saved.tokens.accessToken)
            assertEquals("test-refresh-2", saved.tokens.refreshToken)
        }

    @Test
    fun `signing out revokes the newest refresh token after rotation`() =
        runTest {
            val manager = restoredManager()
            manager.signIn(configuration, testTokens(refreshToken = "test-refresh-1"))
            val session = (manager.state.value as SessionState.SignedIn).session
            server.enqueue(MockResponse(code = 401, body = "Unauthorized"))
            server.enqueue(
                jsonResponse(
                    """{"access_token":"test-access-2","token_type":"Bearer","expires_in":300,"refresh_token":"test-refresh-2"}""",
                ),
            )
            server.enqueue(jsonResponse("""{"articles":null}"""))
            session.newsRepository.listArticles(Shelf.Active)
            repeat(3) { server.nextRequest() }
            server.enqueue(MockResponse(code = 200, body = ""))

            manager.signOut(forgetServer = false)
            advanceUntilIdle()

            val revocation = server.nextRequest()
            assertEquals("/realms/test/protocol/openid-connect/revoke", revocation.url.encodedPath)
            assertEquals("test-refresh-2", revocation.formFields()["token"])
        }

    @Test
    fun `a session the identity provider ends returns to sign-in with the reason`() =
        runTest {
            val manager = restoredManager()
            manager.signIn(configuration, testTokens())
            val session = (manager.state.value as SessionState.SignedIn).session
            server.enqueue(MockResponse(code = 401, body = "Unauthorized"))
            server.enqueue(jsonResponse("""{"error":"invalid_grant"}""", code = 400))

            val failure = runCatching { session.newsRepository.listArticles(Shelf.Active) }.exceptionOrNull()

            assertTrue(failure is SessionEndedException)
            assertEquals(SessionState.SignedOut(configuration.origin, SessionEndReason.Expired), manager.state.value)
            assertEquals(SessionStore.Saved.SignedOut(configuration.origin), store.read())
        }

    private fun TestScope.sessionManager(): SessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SessionManager(
            store = store,
            tokenClient = TokenClient(newHttpClient(), clock),
            httpClient = newHttpClient(),
            clock = clock,
            appScope = this,
            ioDispatcher = dispatcher,
        )
    }

    private suspend fun TestScope.restoredManager(): SessionManager = sessionManager().also { it.restore() }
}
