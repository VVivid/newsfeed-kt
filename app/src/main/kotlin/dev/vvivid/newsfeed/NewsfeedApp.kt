package dev.vvivid.newsfeed

import android.content.Context
import android.graphics.Color
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.vvivid.newsfeed.auth.SessionState
import dev.vvivid.newsfeed.auth.SignInService
import dev.vvivid.newsfeed.auth.SignedInSession
import dev.vvivid.newsfeed.browser.openInBrowser
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.news.NewsRoute
import dev.vvivid.newsfeed.news.NewsViewModel
import dev.vvivid.newsfeed.onboarding.OnboardingRoute
import dev.vvivid.newsfeed.onboarding.OnboardingViewModel
import dev.vvivid.newsfeed.reader.ArticleRoute
import dev.vvivid.newsfeed.reader.ArticleViewModel
import dev.vvivid.newsfeed.settings.LicensesRoute
import dev.vvivid.newsfeed.settings.SettingsRoute
import dev.vvivid.newsfeed.ui.cover.LocalThumbnailLoader
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import dev.vvivid.newsfeed.ui.theme.ThemeMode
import kotlinx.serialization.Serializable

/** The app's screens, as type-safe Navigation routes. */
sealed interface Destination {
    @Serializable
    data object Onboarding : Destination

    @Serializable
    data object News : Destination

    /** @property inTrash whether the article was in Trash when it was opened. */
    @Serializable
    data class Article(
        val articleId: String,
        val inTrash: Boolean,
    ) : Destination

    @Serializable
    data object Settings : Destination

    @Serializable
    data object Licenses : Destination
}

/**
 * The whole app: theme and navigation, following [sessionState]. Signed out, it shows onboarding;
 * signed in, News, the reader and Settings. When the state changes, for example after signing in
 * or when the session ends, the back stack is replaced, so that Back never returns to the screens
 * of a session that is gone.
 *
 * @param onSignOut signs out of the current server, keeping its address for the next sign-in.
 * @param onChangeServer signs out and forgets the server.
 */
@Composable
fun NewsfeedApp(
    sessionState: SessionState,
    signInService: SignInService,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // The appearance choices live here, above the navigation graph, because they theme every
    // screen; Settings only shows and changes them. They survive activity recreation; keeping them
    // across app launches is not part of this version.
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    var useDynamicColor by rememberSaveable { mutableStateOf(false) }
    val darkTheme = themeMode.isDark(systemInDarkTheme = isSystemInDarkTheme())
    SystemBarsStyleEffect(darkTheme)

    NewsfeedTheme(darkTheme = darkTheme, dynamicColor = useDynamicColor) {
        if (sessionState == SessionState.Restoring) {
            // Reading the saved session takes a moment; the window background stays until then.
            Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
            return@NewsfeedTheme
        }
        val session = (sessionState as? SessionState.SignedIn)?.session
        val signedOut = sessionState as? SessionState.SignedOut
        val context = LocalContext.current

        // Decided once: later changes of the session navigate instead (see FollowSession).
        val startDestination: Destination = remember { if (session != null) Destination.News else Destination.Onboarding }
        NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
            composable<Destination.Onboarding> {
                OnboardingRoute(
                    viewModel =
                        viewModel {
                            OnboardingViewModel(
                                savedState = createSavedStateHandle(),
                                signInService = signInService,
                                lastServerOrigin = signedOut?.lastServerOrigin,
                                sessionEndReason = signedOut?.endReason,
                            )
                        },
                )
            }
            // The signed-in screens draw nothing for the moment between the session ending and
            // FollowSession leaving them.
            composable<Destination.News> { entry ->
                if (session == null) return@composable
                CompositionLocalProvider(LocalThumbnailLoader provides session.thumbnails) {
                    NewsRoute(
                        viewModel = viewModel { NewsViewModel(session.newsRepository) },
                        onOpenArticle = { article ->
                            val inTrash = article.shelf == Shelf.Trash
                            entry.ifResumed { navController.navigate(Destination.Article(article.id, inTrash)) }
                        },
                        onOpenSettings = { entry.ifResumed { navController.navigate(Destination.Settings) } },
                    )
                }
            }
            composable<Destination.Article> { entry ->
                if (session == null) return@composable
                CompositionLocalProvider(LocalThumbnailLoader provides session.thumbnails) {
                    ArticleRoute(
                        viewModel =
                            viewModel {
                                val article = entry.toRoute<Destination.Article>()
                                ArticleViewModel(article.articleId, article.inTrash, session.newsRepository)
                            },
                        onBack = { entry.ifResumed { navController.navigateUp() } },
                        // Finished-reading tracking comes with the in-app browser.
                        onOpenOriginal = { url -> openPage(context, url) },
                        onOpenLink = { url -> openPage(context, url) },
                    )
                }
            }
            composable<Destination.Settings> { entry ->
                if (session == null) return@composable
                SettingsRoute(
                    serverOrigin = session.server.origin,
                    signInHost = session.server.signInHost,
                    themeMode = themeMode,
                    onThemeModeChange = { themeMode = it },
                    useDynamicColor = useDynamicColor,
                    onDynamicColorChange = { useDynamicColor = it },
                    onBack = { entry.ifResumed { navController.navigateUp() } },
                    onSignOut = { entry.ifResumed(onSignOut) },
                    onChangeServer = { entry.ifResumed(onChangeServer) },
                    onOpenLicenses = { entry.ifResumed { navController.navigate(Destination.Licenses) } },
                )
            }
            composable<Destination.Licenses> { entry ->
                LicensesRoute(onBack = { entry.ifResumed { navController.navigateUp() } })
            }
        }
        FollowSession(session, navController)
    }
}

/**
 * Moves to News when a session starts and to onboarding when it ends, replacing the whole back
 * stack. The screens of the previous state leave with their view models.
 */
@Composable
private fun FollowSession(
    session: SignedInSession?,
    navController: NavHostController,
) {
    LaunchedEffect(session) {
        val isOnOnboarding = navController.currentDestination?.hasRoute<Destination.Onboarding>() == true
        val target =
            when {
                session != null && isOnOnboarding -> Destination.News
                session == null && !isOnOnboarding -> Destination.Onboarding
                else -> return@LaunchedEffect
            }
        navController.navigate(target) {
            popUpTo(navController.graph.id) { inclusive = true }
        }
    }
}

/** Opens an article's page in the browser, or says that no browser could. */
private fun openPage(
    context: Context,
    url: String,
) {
    if (!openInBrowser(context, url)) Toast.makeText(context, R.string.browser_unavailable, Toast.LENGTH_SHORT).show()
}

/**
 * Runs [navigate] only while this entry's screen is the one on top, so a second tap during a
 * screen transition does not navigate twice.
 */
private inline fun NavBackStackEntry.ifResumed(navigate: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) navigate()
}

/**
 * Keeps the status and navigation bar icons readable in the app's theme, which differs from the
 * system's when the user picks Light or Dark in Settings.
 */
@Composable
private fun SystemBarsStyleEffect(darkTheme: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, darkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(DefaultLightScrim, DefaultDarkScrim) { darkTheme },
        )
        onDispose {}
    }
}

// The navigation bar scrims enableEdgeToEdge() uses by default (androidx.activity's EdgeToEdge.kt).
private val DefaultLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DefaultDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
