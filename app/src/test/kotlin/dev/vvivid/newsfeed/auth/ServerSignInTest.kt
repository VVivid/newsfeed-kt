package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
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

/** Finishing a browser sign-in, with the test server as the identity provider's token endpoint. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServerSignInTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val clock = TestClock(Instant.parse("2026-11-25T12:00:00Z"))
    private lateinit var configuration: ServerConfiguration

    @Before
    fun startServer() {
        server.start()
        val origin = server.url("/").toString().trimEnd('/')
        configuration = testServer(origin = origin, issuerOrigin = origin)
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a finished sign-in starts the session`() =
        runTest {
            val (signIn, sessionManager) = signIn()
            server.enqueue(jsonResponse(tokenResponse(idToken = idToken())))

            signIn.finishSignIn(configuration, authorization())

            val session = (sessionManager.state.value as SessionState.SignedIn).session
            assertEquals(configuration, session.server)
        }

    @Test
    fun `a sign-in with or without offline access starts the session`() =
        runTest {
            val (signIn, sessionManager) = signIn()
            server.enqueue(
                jsonResponse(tokenResponse(idToken = idToken(), scope = "openid offline_access newsfeed:read newsfeed:articles:write")),
            )
            signIn.finishSignIn(configuration, authorization())
            assertTrue(sessionManager.state.value is SessionState.SignedIn)

            // Signing out revokes the refresh token in the background; the test server accepts that.
            server.enqueue(MockResponse(code = 200, body = ""))
            sessionManager.signOut(forgetServer = false)
            advanceUntilIdle()

            // A provider or user that does not allow offline access leaves an ordinary session.
            server.enqueue(jsonResponse(tokenResponse(idToken = idToken(), scope = "openid newsfeed:read newsfeed:articles:write")))
            signIn.finishSignIn(configuration, authorization())
            assertTrue(sessionManager.state.value is SessionState.SignedIn)
        }

    @Test
    fun `absurd times from the identity provider fail the sign-in or are capped, never crash`() =
        runTest {
            // Regression: each of these used to throw an exception the sign-in did not expect.
            val (signIn, sessionManager) = signIn()
            server.enqueue(jsonResponse(tokenResponse(idToken = idToken(issuedAt = Long.MAX_VALUE))))
            assertProblem(SignInProblem.Failed) { signIn.finishSignIn(configuration, authorization()) }

            server.enqueue(jsonResponse(tokenResponse(idToken = idToken(), expiresIn = Long.MAX_VALUE)))
            signIn.finishSignIn(configuration, authorization())
            assertTrue(sessionManager.state.value is SessionState.SignedIn)
        }

    @Test
    fun `an ID token from another sign-in is refused`() =
        runTest {
            val (signIn, sessionManager) = signIn()
            server.enqueue(jsonResponse(tokenResponse(idToken = idToken(nonce = "another-nonce"))))

            assertProblem(SignInProblem.Failed) { signIn.finishSignIn(configuration, authorization()) }
            assertTrue(sessionManager.state.value is SessionState.SignedOut)
        }

    @Test
    fun `a code requested for another server is not exchanged`() =
        runTest {
            val (signIn, _) = signIn()

            assertProblem(SignInProblem.Failed) {
                signIn.finishSignIn(configuration, authorization(tokenEndpoint = "https://auth.example.org/token"))
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a refused exchange and an unreachable provider are told apart`() =
        runTest {
            val (signIn, _) = signIn()
            server.enqueue(jsonResponse("""{"error":"invalid_grant"}""", code = 400))
            server.enqueue(jsonResponse("""{"error":"temporarily_unavailable"}""", code = 503))

            assertProblem(SignInProblem.Rejected) { signIn.finishSignIn(configuration, authorization()) }
            assertProblem(SignInProblem.SignInServerUnreachable) { signIn.finishSignIn(configuration, authorization()) }
        }

    @Test
    fun `a sign-in that does not allow reading articles is refused`() =
        runTest {
            val (signIn, sessionManager) = signIn()
            server.enqueue(jsonResponse(tokenResponse(idToken = idToken(), scope = "openid newsfeed:articles:write")))

            assertProblem(SignInProblem.ReadAccessNotGranted) { signIn.finishSignIn(configuration, authorization()) }
            assertTrue(sessionManager.state.value is SessionState.SignedOut)
        }

    private suspend fun TestScope.signIn(): Pair<ServerSignIn, SessionManager> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val httpClient = newHttpClient()
        val tokenClient = TokenClient(httpClient, clock)
        val sessionManager =
            SessionManager(
                store = SessionStore({ File(folder.root, "session.json") }, TestTokenCipher()),
                tokenClient = tokenClient,
                httpClient = httpClient,
                clock = clock,
                appScope = this,
                ioDispatcher = dispatcher,
            )
        sessionManager.restore()
        return ServerSignIn(ServerDiscovery(httpClient, dispatcher), tokenClient, sessionManager, clock, dispatcher) to sessionManager
    }

    private fun authorization(tokenEndpoint: String = configuration.tokenEndpoint) =
        BrowserAuthorization(
            code = "test-code",
            codeVerifier = "test-verifier",
            nonce = "test-nonce",
            tokenEndpoint = tokenEndpoint,
            resource = configuration.apiResource,
        )

    private fun idToken(
        nonce: String = "test-nonce",
        issuedAt: Long = clock.now.epochSecond,
    ): String {
        val now = clock.now.epochSecond
        return syntheticJwt(
            """{"iss":"${configuration.issuer}","sub":"test-subject","aud":"newsfeed-android","azp":"newsfeed-android",""" +
                """"exp":${now + 300},"iat":$issuedAt,"nonce":"$nonce"}""",
        )
    }

    private fun tokenResponse(
        idToken: String,
        scope: String = "openid newsfeed:read newsfeed:articles:write",
        expiresIn: Long = 300,
    ) = """{"access_token":"test-access","token_type":"Bearer","expires_in":$expiresIn,"refresh_token":"test-refresh",""" +
        """"id_token":"$idToken","scope":"$scope"}"""

    private suspend fun assertProblem(
        problem: SignInProblem,
        block: suspend () -> Unit,
    ) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $problem but got $failure", failure is SignInException && failure.problem == problem)
    }
}
