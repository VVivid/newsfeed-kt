package dev.vvivid.newsfeed.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock

/** Why a sign-in did not finish. */
enum class SignInProblem {
    /** The user left the sign-in page before finishing. */
    Cancelled,

    /** The user did not allow the app access. */
    AccessDenied,

    /** No browser could open the sign-in page. */
    NoBrowser,

    /** The identity provider could not be reached to finish signing in. */
    SignInServerUnreachable,

    /** The identity provider would not issue tokens for this sign-in. */
    Rejected,

    /** The sign-in did not include permission to read articles. */
    ReadAccessNotGranted,

    /** A security check failed, or an answer could not be used. */
    Failed,
}

class SignInException(
    val problem: SignInProblem,
    cause: Throwable? = null,
) : Exception("Sign-in failed: $problem", cause)

/** What onboarding needs from the app to sign in. */
interface SignInService {
    /** Finds out how to sign in to the server at [origin]. Throws [DiscoveryException]. */
    suspend fun discover(origin: String): ServerConfiguration

    /** Finishes a browser sign-in to [server] and starts its session. Throws [SignInException]. */
    suspend fun finishSignIn(
        server: ServerConfiguration,
        authorization: BrowserAuthorization,
    )
}

/**
 * Signs in with [discovery] and the token endpoint, and hands the result to [sessionManager]. After
 * the browser returns, the code is exchanged for tokens; the ID token must belong to this sign-in
 * (see [checkIdToken]) and the grant must allow reading articles before the session starts.
 */
class ServerSignIn(
    private val discovery: ServerDiscovery,
    private val tokenClient: TokenClient,
    private val sessionManager: SessionManager,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SignInService {
    override suspend fun discover(origin: String): ServerConfiguration = discovery.discover(origin)

    override suspend fun finishSignIn(
        server: ServerConfiguration,
        authorization: BrowserAuthorization,
    ) {
        // The code may only be exchanged at, and for, the server it was requested for.
        if (authorization.tokenEndpoint != server.tokenEndpoint || authorization.resource != server.apiResource) {
            throw SignInException(SignInProblem.Failed)
        }
        val grant =
            withContext(ioDispatcher) {
                try {
                    tokenClient.exchangeCode(server, authorization.code, authorization.codeVerifier)
                } catch (e: TokenRequestRejectedException) {
                    throw SignInException(SignInProblem.Rejected, e)
                } catch (e: IOException) {
                    throw SignInException(SignInProblem.SignInServerUnreachable, e)
                }
            }
        val idToken = grant.idToken ?: throw SignInException(SignInProblem.Failed)
        try {
            checkIdToken(idToken, server.issuer, NativeClient.ID, authorization.nonce, clock.instant())
        } catch (e: InvalidIdTokenException) {
            throw SignInException(SignInProblem.Failed, e)
        }
        if (grant.grantedScopes?.contains(NewsfeedScopes.READ) == false) throw SignInException(SignInProblem.ReadAccessNotGranted)
        try {
            sessionManager.signIn(server, Tokens.from(grant))
        } catch (e: IOException) {
            // The session could not be saved.
            throw SignInException(SignInProblem.Failed, e)
        }
    }
}
