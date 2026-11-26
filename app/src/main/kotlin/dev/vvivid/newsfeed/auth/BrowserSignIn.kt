package dev.vvivid.newsfeed.auth

import android.content.Intent
import androidx.core.net.toUri
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

// The sign-in page runs in the user's browser, in a Custom Tab, through AppAuth. AppAuth makes the
// random state, nonce and PKCE code verifier, opens the page, receives the redirect back to the app
// and checks that its state matches the request. This file turns the app's server configuration
// into AppAuth's request, and AppAuth's answer into the app's own types.

/**
 * A finished browser sign-in: the authorization code, with the PKCE code verifier and the nonce of
 * the request it answers. [tokenEndpoint] and [resource] also come from that request, so that the
 * code is only exchanged for the server it was requested for. [toString] hides the secrets.
 */
class BrowserAuthorization(
    val code: String,
    val codeVerifier: String,
    val nonce: String,
    val tokenEndpoint: String,
    val resource: String?,
) {
    override fun toString(): String = "BrowserAuthorization(hidden)"
}

/** What the browser sign-in returned. */
sealed interface BrowserSignInResult {
    class Authorized(
        val authorization: BrowserAuthorization,
    ) : BrowserSignInResult

    data class Failed(
        val problem: SignInProblem,
    ) : BrowserSignInResult
}

/**
 * The request that opens [server]'s sign-in page: the authorization code flow with PKCE S256, the
 * app's scopes, and the API as `resource` (RFC 8707). Throws [SignInException] if the device cannot
 * make an S256 challenge, in which case AppAuth would fall back to the weaker plain method.
 */
fun authorizationRequest(server: ServerConfiguration): AuthorizationRequest {
    val configuration = AuthorizationServiceConfiguration(server.authorizationEndpoint.toUri(), server.tokenEndpoint.toUri())
    val request =
        AuthorizationRequest
            .Builder(configuration, NativeClient.ID, ResponseTypeValues.CODE, NativeClient.REDIRECT_URI.toUri())
            .setScope(NativeClient.SCOPE)
            .setAdditionalParameters(mapOf(RESOURCE_PARAMETER to server.apiResource))
            .build()
    if (request.codeVerifierChallengeMethod != AuthorizationRequest.CODE_CHALLENGE_METHOD_S256) throw SignInException(SignInProblem.Failed)
    return request
}

/**
 * Reads the result that AppAuth's sign-in activity returned in [data]. No data at all means the
 * activity closed without an answer, which happens when the user leaves the sign-in page.
 */
fun browserSignInResult(data: Intent?): BrowserSignInResult {
    if (data == null) return BrowserSignInResult.Failed(SignInProblem.Cancelled)
    return try {
        val response = AuthorizationResponse.fromIntent(data)
        if (response != null) authorized(response) else failed(AuthorizationException.fromIntent(data))
    } catch (e: IllegalArgumentException) {
        // AppAuth could not read its own extras.
        BrowserSignInResult.Failed(SignInProblem.Failed)
    }
}

private fun authorized(response: AuthorizationResponse): BrowserSignInResult {
    val request = response.request
    val code = response.authorizationCode
    val codeVerifier = request.codeVerifier
    val nonce = request.nonce
    if (code == null || codeVerifier == null || nonce == null ||
        request.codeVerifierChallengeMethod != AuthorizationRequest.CODE_CHALLENGE_METHOD_S256
    ) {
        return BrowserSignInResult.Failed(SignInProblem.Failed)
    }
    return BrowserSignInResult.Authorized(
        BrowserAuthorization(
            code = code,
            codeVerifier = codeVerifier,
            nonce = nonce,
            tokenEndpoint = request.configuration.tokenEndpoint.toString(),
            resource = request.additionalParameters[RESOURCE_PARAMETER],
        ),
    )
}

/** AppAuth's errors compare equal by their type and code. */
private fun failed(exception: AuthorizationException?): BrowserSignInResult {
    val problem =
        when (exception) {
            null, AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW -> SignInProblem.Cancelled

            // AppAuth's sign-in activity reports this when no browser could open the page.
            AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW -> SignInProblem.NoBrowser

            AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED -> SignInProblem.AccessDenied

            // Includes a state that does not match the request, which AppAuth rejects.
            else -> SignInProblem.Failed
        }
    return BrowserSignInResult.Failed(problem)
}

/** RFC 8707's resource indicator: which API the tokens are for. */
private const val RESOURCE_PARAMETER = "resource"
