package dev.vvivid.newsfeed.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * A session's OAuth tokens, as they are kept (encrypted) between launches. Not a data class, and
 * [toString] hides them, so that they cannot end up in a log by accident.
 */
@Serializable
class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_token_expires_at") val accessTokenExpiresAtMillis: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
) {
    /** Whether the access token will have expired by [time]. */
    fun accessTokenExpiresBy(time: Instant): Boolean = accessTokenExpiresAtMillis <= time.toEpochMilli()

    override fun toString(): String = "Tokens(hidden)"

    companion object {
        /**
         * The tokens that [grant] issued. A refresh that issues no new refresh token leaves
         * [previousRefreshToken] valid, so it is kept.
         */
        fun from(
            grant: TokenGrant,
            previousRefreshToken: String? = null,
        ): Tokens = Tokens(grant.accessToken, grant.accessTokenExpiresAt.toEpochMilli(), grant.refreshToken ?: previousRefreshToken)
    }
}

/** The session ended: the user signed out, or it can no longer be renewed. No credentials were sent. */
class SessionEndedException(
    cause: Throwable? = null,
) : IOException("The session has ended", cause)

/** Why a session ended without the user signing out. */
enum class SessionEndReason {
    /** The identity provider no longer accepts the refresh token: the sign-in expired or was revoked. */
    Expired,

    /**
     * The server refused even a newly issued access token, for example because the account may no
     * longer use this server.
     */
    NotAccepted,
}

/**
 * The tokens of one signed-in session, and the only place they change. [AuthInterceptor] asks for an
 * access token before each API request and again after the server rejects one.
 *
 * Refreshes happen one at a time under a lock, and requests that need a new token at the same
 * moment share one refresh. The identity provider may rotate refresh tokens, accepting each one only
 * once: a refresh replaces the tokens in memory and saves them before it releases the lock, so the
 * next refresh always sends the newest refresh token, and an old one is never sent again.
 *
 * The methods block on the network while refreshing, so they are called on OkHttp's threads, never
 * the main thread.
 *
 * @param onRefreshed saves newly issued tokens; it runs before the refresh that issued them ends.
 * @param onEnded reports, once, that the session cannot continue.
 */
class SessionTokens(
    private val server: ServerConfiguration,
    initialTokens: Tokens,
    private val tokenClient: TokenClient,
    private val clock: Clock,
    private val onRefreshed: (Tokens) -> Unit,
    private val onEnded: (SessionEndReason) -> Unit,
) {
    private val refreshLock = Any()

    /**
     * The current tokens, or null once the session has ended or was closed. Only a refresh, under
     * [refreshLock], replaces them with newer ones; ending or closing the session clears them without
     * waiting for a refresh in progress.
     */
    private val tokens = AtomicReference<Tokens?>(initialTokens)

    /**
     * An access token that will not expire within the next [EXPIRY_MARGIN], refreshing first if
     * needed. Throws [SessionEndedException] once the session has ended, and another [IOException]
     * when a needed refresh could not reach the identity provider; the session then stays as it is.
     */
    fun accessToken(): String =
        synchronized(refreshLock) {
            val current = tokens.get() ?: throw SessionEndedException()
            if (current.accessTokenExpiresBy(clock.instant() + EXPIRY_MARGIN)) refresh(current).accessToken else current.accessToken
        }

    /**
     * An access token to retry with after the server rejected [rejected] with HTTP 401: the one
     * another request has refreshed meanwhile, or else a newly refreshed one.
     */
    fun accessTokenReplacing(rejected: String): String =
        synchronized(refreshLock) {
            val current = tokens.get() ?: throw SessionEndedException()
            if (current.accessToken != rejected) current.accessToken else refresh(current).accessToken
        }

    /** The server rejected a freshly refreshed access token, so refreshing again cannot help. */
    fun notAccepted() = end(SessionEndReason.NotAccepted)

    /**
     * The user signed out: from now on no request gets a token. Does not wait for a refresh in
     * progress. Returns the refresh token, so that it can be revoked.
     */
    fun close(): String? = tokens.getAndSet(null)?.refreshToken

    /** Must be called holding [refreshLock]. */
    private fun refresh(current: Tokens): Tokens {
        // Without a refresh token the sign-in cannot be renewed: it ends with its access token.
        val refreshToken = current.refreshToken ?: endAndThrow(SessionEndReason.Expired, cause = null)
        val grant =
            try {
                tokenClient.refresh(server, refreshToken)
            } catch (e: TokenRequestRejectedException) {
                endAndThrow(SessionEndReason.Expired, e)
            }
        // Other failures, such as no network, propagate without ending the session: the next request
        // tries again.
        val refreshed = Tokens.from(grant, previousRefreshToken = refreshToken)
        // The user may have signed out while the refresh was on its way; then its tokens are dropped.
        if (!tokens.compareAndSet(current, refreshed)) throw SessionEndedException()
        onRefreshed(refreshed)
        return refreshed
    }

    private fun endAndThrow(
        reason: SessionEndReason,
        cause: Throwable?,
    ): Nothing {
        end(reason)
        throw SessionEndedException(cause)
    }

    private fun end(reason: SessionEndReason) {
        if (tokens.getAndSet(null) != null) onEnded(reason)
    }

    private companion object {
        /** Refresh this long before expiry, so that a token does not expire on its way to the server. */
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
    }
}
