package dev.vvivid.newsfeed.auth

import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs with Robolectric for AppAuth's use of Android's Uri, Intent and JSON. */
@RunWith(RobolectricTestRunner::class)
class BrowserSignInTest {
    private val server = testServer()

    @Test
    fun `the sign-in page is asked for a code with PKCE S256, offline access, the app's scopes and the API as resource`() {
        val request = authorizationRequest(server)

        val uri = request.toUri()
        assertEquals(
            server.authorizationEndpoint,
            uri
                .buildUpon()
                .clearQuery()
                .build()
                .toString(),
        )
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals("newsfeed-android", uri.getQueryParameter("client_id"))
        assertEquals("dev.vvivid.newsfeed:/oauth2redirect", uri.getQueryParameter("redirect_uri"))
        assertEquals("openid offline_access newsfeed:read newsfeed:articles:write", uri.getQueryParameter("scope"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals(server.apiResource, uri.getQueryParameter("resource"))
        assertNotNull(uri.getQueryParameter("code_challenge"))
        assertNotNull(uri.getQueryParameter("state"))
        assertNotNull(uri.getQueryParameter("nonce"))
        // The verifier stays in the app; only its challenge goes to the browser.
        assertFalse(uri.toString().contains(request.codeVerifier.orEmpty()))
    }

    @Test
    fun `each sign-in gets its own state, nonce and verifier`() {
        val first = authorizationRequest(server)
        val second = authorizationRequest(server)

        assertTrue(first.state != second.state && first.nonce != second.nonce && first.codeVerifier != second.codeVerifier)
    }

    @Test
    fun `a finished sign-in gives the code with the verifier and nonce of its request`() {
        val request = authorizationRequest(server)
        val response =
            AuthorizationResponse
                .Builder(request)
                .setAuthorizationCode("test-code")
                .setState(request.state)
                .build()

        val result = browserSignInResult(response.toIntent()) as BrowserSignInResult.Authorized

        val authorization = result.authorization
        assertEquals("test-code", authorization.code)
        assertEquals(request.codeVerifier, authorization.codeVerifier)
        assertEquals(request.nonce, authorization.nonce)
        assertEquals(server.tokenEndpoint, authorization.tokenEndpoint)
        assertEquals(server.apiResource, authorization.resource)
        assertFalse(authorization.toString().contains("test-code"))
    }

    @Test
    fun `leaving the sign-in page cancels`() {
        assertEquals(BrowserSignInResult.Failed(SignInProblem.Cancelled), browserSignInResult(null))
        assertEquals(
            BrowserSignInResult.Failed(SignInProblem.Cancelled),
            browserSignInResult(AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.toIntent()),
        )
    }

    @Test
    fun `AppAuth's errors become the app's sign-in problems`() {
        assertEquals(
            BrowserSignInResult.Failed(SignInProblem.AccessDenied),
            browserSignInResult(AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED.toIntent()),
        )
        assertEquals(
            BrowserSignInResult.Failed(SignInProblem.NoBrowser),
            browserSignInResult(AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW.toIntent()),
        )
        assertEquals(
            BrowserSignInResult.Failed(SignInProblem.Failed),
            browserSignInResult(AuthorizationException.AuthorizationRequestErrors.STATE_MISMATCH.toIntent()),
        )
    }
}
