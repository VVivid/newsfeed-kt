package dev.vvivid.newsfeed

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.vvivid.newsfeed.data.NewsRepository
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.news.NewsRoute
import dev.vvivid.newsfeed.news.NewsViewModel
import dev.vvivid.newsfeed.onboarding.OnboardingRoute
import dev.vvivid.newsfeed.reader.ArticleRoute
import dev.vvivid.newsfeed.reader.ArticleViewModel
import dev.vvivid.newsfeed.settings.SettingsRoute
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import dev.vvivid.newsfeed.ui.theme.ThemeMode
import kotlinx.serialization.Serializable

/**
 * A signed-in connection to one server: what the screens after onboarding need. This version
 * cannot sign in, so the running app never has one; tests create one around a fake repository.
 */
class SignedInSession(
    /** The server's normalized origin, such as `https://news.example.com`. */
    val serverOrigin: String,
    val newsRepository: NewsRepository,
)

/** The app's screens, as type-safe Navigation routes. */
sealed interface Destination {
    @Serializable
    data object Onboarding : Destination

    @Serializable
    data object News : Destination

    /** @property inTrash whether the article is in Trash, where the server only looks when asked. */
    @Serializable
    data class Article(
        val articleId: String,
        val inTrash: Boolean,
    ) : Destination

    @Serializable
    data object Settings : Destination
}

/**
 * The whole app: theme and navigation, starting on onboarding. The News, Article and Settings
 * screens are part of the navigation graph only while there is a [session].
 */
@Composable
fun NewsfeedApp(
    session: SignedInSession?,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // The appearance choices live here, above the navigation graph, because they theme every
    // screen; Settings only shows and changes them. They survive activity recreation; keeping them
    // across app launches comes with app storage.
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    var useDynamicColor by rememberSaveable { mutableStateOf(false) }
    val darkTheme = themeMode.isDark(systemInDarkTheme = isSystemInDarkTheme())
    SystemBarsStyleEffect(darkTheme)

    NewsfeedTheme(darkTheme = darkTheme, dynamicColor = useDynamicColor) {
        NavHost(navController = navController, startDestination = Destination.Onboarding, modifier = modifier) {
            composable<Destination.Onboarding> { OnboardingRoute() }
            // Without a session, onboarding is the whole graph.
            if (session == null) return@NavHost

            // Signing out and clearing the old server's data come with sign-in. Until then,
            // leaving the server only returns to onboarding with nothing to go back to.
            val leaveServer = {
                navController.navigate(Destination.Onboarding) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
            composable<Destination.News> { entry ->
                NewsRoute(
                    viewModel = viewModel { NewsViewModel(session.newsRepository) },
                    onOpenArticle = { article ->
                        val inTrash = article.shelf == Shelf.Trash
                        entry.ifResumed { navController.navigate(Destination.Article(article.id, inTrash)) }
                    },
                    onOpenSettings = { entry.ifResumed { navController.navigate(Destination.Settings) } },
                )
            }
            composable<Destination.Article> { entry ->
                ArticleRoute(
                    viewModel =
                        viewModel {
                            val article = entry.toRoute<Destination.Article>()
                            ArticleViewModel(article.articleId, article.inTrash, session.newsRepository)
                        },
                    onBack = { entry.ifResumed { navController.navigateUp() } },
                    // The in-app browser checkpoint opens these in a Custom Tab.
                    onOpenOriginal = {},
                    onOpenLink = {},
                )
            }
            composable<Destination.Settings> { entry ->
                SettingsRoute(
                    serverOrigin = session.serverOrigin,
                    themeMode = themeMode,
                    onThemeModeChange = { themeMode = it },
                    useDynamicColor = useDynamicColor,
                    onDynamicColorChange = { useDynamicColor = it },
                    onBack = { entry.ifResumed { navController.navigateUp() } },
                    onSignOut = { entry.ifResumed(leaveServer) },
                    onChangeServer = { entry.ifResumed(leaveServer) },
                )
            }
        }
    }
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
