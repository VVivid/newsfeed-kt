package dev.vvivid.newsfeed.onboarding

import dev.vvivid.newsfeed.auth.BrowserAuthorization
import dev.vvivid.newsfeed.auth.DiscoveryException
import dev.vvivid.newsfeed.auth.ServerConfiguration
import dev.vvivid.newsfeed.auth.SignInException
import dev.vvivid.newsfeed.auth.SignInService
import dev.vvivid.newsfeed.auth.testServer
import kotlinx.coroutines.CompletableDeferred

/** A [SignInService] that discovers [server] and records finished sign-ins, for onboarding tests. */
class FakeSignInService(
    var server: ServerConfiguration = testServer(),
) : SignInService {
    /** While set, discovery fails with it. */
    var discoveryFailure: DiscoveryException? = null

    /** While set, finishing a sign-in fails with it. */
    var signInFailure: SignInException? = null

    /** While set, discovery waits for it to complete, like a slow server. */
    var heldDiscovery: CompletableDeferred<Unit>? = null

    val discoveredOrigins = mutableListOf<String>()
    val finishedSignIns = mutableListOf<Pair<ServerConfiguration, BrowserAuthorization>>()

    override suspend fun discover(origin: String): ServerConfiguration {
        discoveredOrigins += origin
        heldDiscovery?.await()
        discoveryFailure?.let { throw it }
        return server
    }

    override suspend fun finishSignIn(
        server: ServerConfiguration,
        authorization: BrowserAuthorization,
    ) {
        finishedSignIns += server to authorization
        signInFailure?.let { throw it }
    }
}
