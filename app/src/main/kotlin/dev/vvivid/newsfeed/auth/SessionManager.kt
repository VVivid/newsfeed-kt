package dev.vvivid.newsfeed.auth

import dev.vvivid.newsfeed.data.NewsRepository
import dev.vvivid.newsfeed.data.ServerNewsRepository
import dev.vvivid.newsfeed.data.ThumbnailLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Clock

/** Whether the app is signed in, as the screens see it. */
sealed interface SessionState {
    /** The saved session is being read at app start, which takes a moment. */
    data object Restoring : SessionState

    /**
     * There is no session.
     *
     * @property lastServerOrigin the server signed in to before, which onboarding offers again.
     * @property endReason why the session ended, when it ended without the user signing out.
     */
    data class SignedOut(
        val lastServerOrigin: String? = null,
        val endReason: SessionEndReason? = null,
    ) : SessionState

    data class SignedIn(
        val session: SignedInSession,
    ) : SessionState
}

/**
 * A signed-in connection to one server: what the screens after onboarding need.
 *
 * @property thumbnails loads the server's article thumbnails; null shows fallback covers only, as in
 *   tests and previews.
 */
class SignedInSession(
    val server: ServerConfiguration,
    val newsRepository: NewsRepository,
    val thumbnails: ThumbnailLoader? = null,
)

/**
 * Owns the app's session: restores it at start, starts one after sign-in, and ends it on sign-out or
 * when it can no longer be renewed. [state] tells the screens which of these applies.
 *
 * Each session has its own API client, which carries that session's tokens (see [AuthInterceptor]).
 * Once the session ends, the client stops sending credentials, so requests still on their way fail
 * instead of reaching the server with an old token.
 *
 * @param appScope outlives the screens, so that signing out finishes after Settings has closed.
 */
class SessionManager(
    private val store: SessionStore,
    private val tokenClient: TokenClient,
    private val httpClient: OkHttpClient,
    private val clock: Clock,
    private val appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Guards [active] and the [state] changes that go with it. */
    private val lock = Any()

    /** The running session, or null when signed out. */
    private var active: ActiveSession? = null

    /**
     * Serializes writes to [store]. A write for a session first checks that it is still the active
     * one, so a refresh that finishes after sign-out cannot save its tokens again.
     *
     * Locks are taken in one order only: a session's refresh lock (in [SessionTokens]), then
     * [storeLock], then [lock]. [SessionTokens] calls [saveRefreshedTokens] and [onSessionEnded]
     * while it may hold its refresh lock, so code holding [storeLock] or [lock] must never wait for
     * an access token. [SessionTokens.close] takes no lock, so closing a session under [lock] is safe.
     */
    private val storeLock = Any()

    /**
     * Reads the saved session at app start; [state] leaves [SessionState.Restoring]. Does nothing
     * once [state] has left it, so a sign-in that finished while the file was read is kept.
     */
    suspend fun restore() {
        val saved = withContext(ioDispatcher) { store.read() }
        synchronized(lock) {
            if (_state.value != SessionState.Restoring) return
            _state.value =
                when (saved) {
                    is SessionStore.Saved.SignedIn -> SessionState.SignedIn(start(saved.server, saved.tokens).session)
                    is SessionStore.Saved.SignedOut -> SessionState.SignedOut(saved.lastServerOrigin)
                }
        }
    }

    /** Saves and starts the session that a sign-in to [server] issued [tokens] for. */
    suspend fun signIn(
        server: ServerConfiguration,
        tokens: Tokens,
    ) {
        withContext(ioDispatcher) { synchronized(storeLock) { store.saveSignedIn(server, tokens) } }
        synchronized(lock) {
            // Sign-in starts from onboarding, so there is normally no session to replace.
            active?.close()
            _state.value = SessionState.SignedIn(start(server, tokens).session)
        }
    }

    /**
     * Signs out of the current server. Its tokens stop being used at once; then, in the background,
     * they are deleted from storage and the refresh token is revoked at the identity provider if it
     * offers that. Unless [forgetServer], onboarding offers the same server again.
     */
    fun signOut(forgetServer: Boolean) {
        val ended: ActiveSession
        val refreshToken: String?
        val lastServerOrigin: String?
        synchronized(lock) {
            ended = active ?: return
            active = null
            refreshToken = ended.close()
            lastServerOrigin = ended.server.origin.takeUnless { forgetServer }
            _state.value = SessionState.SignedOut(lastServerOrigin)
        }
        appScope.launch(ioDispatcher) {
            saveSignedOut(lastServerOrigin)
            if (refreshToken != null) {
                try {
                    tokenClient.revoke(ended.server, refreshToken)
                } catch (e: IOException) {
                    // Best effort: the token is already gone from the phone and expires on its own.
                }
            }
        }
    }

    /** Called on an OkHttp thread when [session] can no longer be renewed. */
    private fun onSessionEnded(
        session: ActiveSession,
        reason: SessionEndReason,
    ) {
        synchronized(lock) {
            if (active !== session) return
            active = null
            session.close()
            _state.value = SessionState.SignedOut(session.server.origin, reason)
        }
        saveSignedOut(session.server.origin)
    }

    /** Called on an OkHttp thread with the tokens a refresh of [session] issued. */
    private fun saveRefreshedTokens(
        session: ActiveSession,
        tokens: Tokens,
    ) {
        synchronized(storeLock) {
            if (synchronized(lock) { active !== session }) return
            try {
                store.saveSignedIn(session.server, tokens)
            } catch (e: IOException) {
                // The request goes on with the new tokens in memory. If they cannot be saved, the next
                // launch starts with the previous ones and may have to sign in again.
            }
        }
    }

    private fun saveSignedOut(lastServerOrigin: String?) {
        synchronized(storeLock) {
            try {
                store.saveSignedOut(lastServerOrigin)
            } catch (e: IOException) {
                // The tokens are no longer used. If the file keeps them, the next launch finds them
                // expired or revoked and signs out again.
            }
        }
    }

    /** Must be called holding [lock]. */
    private fun start(
        server: ServerConfiguration,
        tokens: Tokens,
    ): ActiveSession = ActiveSession(server, tokens).also { active = it }

    /** A running session: its tokens and the clients that use them. */
    private inner class ActiveSession(
        val server: ServerConfiguration,
        initialTokens: Tokens,
    ) {
        private val tokens =
            SessionTokens(
                server = server,
                initialTokens = initialTokens,
                tokenClient = tokenClient,
                clock = clock,
                onRefreshed = { saveRefreshedTokens(this, it) },
                onEnded = { onSessionEnded(this, it) },
            )

        /** Shares the base client's connections and threads, adding this session's credentials. */
        private val apiClient = httpClient.newBuilder().addInterceptor(AuthInterceptor(server.origin.toHttpUrl(), tokens)).build()

        private val thumbnails = ThumbnailLoader(apiClient, server.origin, ioDispatcher)

        val session = SignedInSession(server, ServerNewsRepository(apiClient, server.origin, ioDispatcher), thumbnails)

        /** Stops using the tokens and drops cached images. Returns the refresh token, for revoking. */
        fun close(): String? {
            thumbnails.clear()
            return tokens.close()
        }
    }
}
