package dev.vvivid.newsfeed

import android.app.Application
import android.content.Context
import dev.vvivid.newsfeed.auth.KeystoreTokenCipher
import dev.vvivid.newsfeed.auth.ServerDiscovery
import dev.vvivid.newsfeed.auth.ServerSignIn
import dev.vvivid.newsfeed.auth.SessionManager
import dev.vvivid.newsfeed.auth.SessionStore
import dev.vvivid.newsfeed.auth.SignInService
import dev.vvivid.newsfeed.auth.TokenClient
import dev.vvivid.newsfeed.net.newHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock

/** Creates the app's long-lived objects once per process and starts restoring the saved session. */
class NewsfeedApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** The objects that live as long as the app, wired by hand. */
class AppContainer(
    context: Context,
) {
    /** Work that must finish even when no screen waits for it, such as signing out. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock.systemUTC()
    private val httpClient = newHttpClient()
    private val tokenClient = TokenClient(httpClient, clock)

    val sessionManager =
        SessionManager(
            // Not backed up (backups are off anyway): the tokens belong to this device's Keystore key.
            store = SessionStore(file = { File(context.noBackupFilesDir, "session.json") }, cipher = KeystoreTokenCipher()),
            tokenClient = tokenClient,
            httpClient = httpClient,
            clock = clock,
            appScope = appScope,
        )

    val signInService: SignInService = ServerSignIn(ServerDiscovery(httpClient), tokenClient, sessionManager, clock)

    fun start() {
        appScope.launch { sessionManager.restore() }
    }
}
