package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.net.formFields
import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
import dev.vvivid.newsfeed.net.nextRequest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

class TokenClientTest {
    private val server = MockWebServer()
    private val clock = TestClock(Instant.parse("2026-11-25T12:00:00Z"))
    private val tokenClient = TokenClient(newHttpClient(), clock)
    private lateinit var configuration: ServerConfiguration

    @Before
    fun startServer() {
        server.start()
        // The identity provider at the test server's plain-http address; discovery, not this
        // client, is what insists on https.
        val origin = server.url("/").toString().trimEnd('/')
        configuration = testServer(origin = origin, issuerOrigin = origin)
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a code exchange proves the code with PKCE and asks for the API as resource`() {
        server.enqueue(jsonResponse(tokenResponse(idToken = "test-id-token")))

        val grant = tokenClient.exchangeCode(configuration, code = "test-code", codeVerifier = "test-verifier")

        val request = server.nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/realms/test/protocol/openid-connect/token", request.url.encodedPath)
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to "test-code",
                "redirect_uri" to "dev.vvivid.newsfeed:/oauth2redirect",
                "code_verifier" to "test-verifier",
                "client_id" to "newsfeed-android",
                "resource" to configuration.apiResource,
            ),
            request.formFields(),
        )
        assertNull(request.headers["Authorization"])
        assertEquals("test-access", grant.accessToken)
        assertEquals("test-refresh", grant.refreshToken)
        assertEquals("test-id-token", grant.idToken)
        assertEquals(setOf("openid", "newsfeed:read", "newsfeed:articles:write"), grant.grantedScopes)
        assertEquals(clock.now + Duration.ofSeconds(300), grant.accessTokenExpiresAt)
    }

    @Test
    fun `a refresh asks for the API as resource`() {
        server.enqueue(jsonResponse(tokenResponse()))

        tokenClient.refresh(configuration, refreshToken = "test-refresh-old")

        assertEquals(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "test-refresh-old",
                "client_id" to "newsfeed-android",
                "resource" to configuration.apiResource,
            ),
            server.nextRequest().formFields(),
        )
    }

    @Test
    fun `a refused grant is a rejection`() {
        server.enqueue(jsonResponse("""{"error":"invalid_grant","error_description":"Session not active"}""", code = 400))

        val rejection = assertThrows(TokenRequestRejectedException::class.java) { tokenClient.refresh(configuration, "test-refresh") }

        assertEquals(400, rejection.status)
    }

    @Test
    fun `a server error is a failure but not a rejection`() {
        server.enqueue(jsonResponse("""{"error":"temporarily_unavailable"}""", code = 503))

        val failure = assertThrows(IOException::class.java) { tokenClient.refresh(configuration, "test-refresh") }

        assertFalse(failure is TokenRequestRejectedException)
    }

    @Test
    fun `an answer without a bearer access token is not accepted`() {
        server.enqueue(jsonResponse("""{"access_token":"test-access","token_type":"mac","expires_in":300}"""))
        server.enqueue(jsonResponse("""{"token_type":"Bearer","expires_in":300}"""))

        assertThrows(IOException::class.java) { tokenClient.refresh(configuration, "test-refresh") }
        assertThrows(IOException::class.java) { tokenClient.refresh(configuration, "test-refresh") }
    }

    @Test
    fun `an absurd lifetime is capped at a year instead of overflowing`() {
        // Regression: expires_in near Long.MAX_VALUE used to throw while computing the expiry.
        server.enqueue(jsonResponse("""{"access_token":"test-access","token_type":"Bearer","expires_in":9223372036854775807}"""))

        val grant = tokenClient.refresh(configuration, "test-refresh")

        assertEquals(clock.now + Duration.ofDays(365), grant.accessTokenExpiresAt)
        assertEquals(grant.accessTokenExpiresAt.toEpochMilli(), Tokens.from(grant).accessTokenExpiresAtMillis)
    }

    @Test
    fun `a missing or non-positive lifetime counts as a short one`() {
        server.enqueue(jsonResponse("""{"access_token":"test-access","token_type":"Bearer"}"""))
        server.enqueue(jsonResponse("""{"access_token":"test-access","token_type":"Bearer","expires_in":-5}"""))

        assertEquals(clock.now + Duration.ofSeconds(60), tokenClient.refresh(configuration, "test-refresh").accessTokenExpiresAt)
        assertEquals(clock.now + Duration.ofSeconds(60), tokenClient.refresh(configuration, "test-refresh").accessTokenExpiresAt)
    }

    @Test
    fun `a lifetime that is not a whole number of seconds is not accepted`() {
        server.enqueue(jsonResponse("""{"access_token":"test-access","token_type":"Bearer","expires_in":1e30}"""))
        server.enqueue(jsonResponse("""{"access_token":"test-access","token_type":"Bearer","expires_in":99999999999999999999}"""))

        assertThrows(IOException::class.java) { tokenClient.refresh(configuration, "test-refresh") }
        assertThrows(IOException::class.java) { tokenClient.refresh(configuration, "test-refresh") }
    }

    @Test
    fun `revoking sends the refresh token with its type`() {
        server.enqueue(jsonResponse(""))

        tokenClient.revoke(configuration, "test-refresh")

        val request = server.nextRequest()
        assertEquals("/realms/test/protocol/openid-connect/revoke", request.url.encodedPath)
        assertEquals(
            mapOf("token" to "test-refresh", "token_type_hint" to "refresh_token", "client_id" to "newsfeed-android"),
            request.formFields(),
        )
    }

    @Test
    fun `nothing is revoked when the issuer offers no revocation`() {
        tokenClient.revoke(configuration.copy(revocationEndpoint = null), "test-refresh")

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a grant never shows its tokens as text`() {
        server.enqueue(jsonResponse(tokenResponse()))

        val grant = tokenClient.refresh(configuration, "test-refresh")

        assertFalse(grant.toString().contains("test-access"))
        assertTrue(grant.toString().contains("hidden"))
    }

    private fun tokenResponse(idToken: String? = null): String {
        val idTokenField = idToken?.let { ""","id_token":"$it"""" }.orEmpty()
        return """{"access_token":"test-access","token_type":"Bearer","expires_in":300,"refresh_token":"test-refresh",""" +
            """"refresh_expires_in":1800,"scope":"openid newsfeed:read newsfeed:articles:write"$idTokenField}"""
    }
}
