package dev.vvivid.newsfeed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.vvivid.newsfeed.auth.SessionEndReason
import dev.vvivid.newsfeed.auth.SessionState
import dev.vvivid.newsfeed.auth.SignedInSession
import dev.vvivid.newsfeed.auth.testServer
import dev.vvivid.newsfeed.data.FakeNewsRepository
import dev.vvivid.newsfeed.data.testArticle
import dev.vvivid.newsfeed.onboarding.FakeSignInService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app's navigation as the session starts and ends; the session itself is faked. */
@RunWith(RobolectricTestRunner::class)
class NewsfeedNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val article = testArticle("navigation")
    private val repository = FakeNewsRepository(listOf(article), bodies = mapOf(article.id to "The saved body."))
    private val session = SignedInSession(testServer(), repository)
    private var sessionState: SessionState by mutableStateOf(SessionState.SignedOut())
    private var signOuts = 0
    private var serverChanges = 0
    private lateinit var navController: NavHostController

    @Test
    fun `signing in opens News, and signing out returns to onboarding with the server filled in`() {
        setApp(SessionState.SignedOut(lastServerOrigin = "https://news.example.com"))
        composeRule.onNodeWithText("Connect to your Newsfeed").assertIsDisplayed()
        composeRule.onNodeWithText("news.example.com").assertIsDisplayed()

        sessionState = SessionState.SignedIn(session)
        composeRule.onNodeWithText(article.title).performClick()
        composeRule.onNodeWithText("The saved body.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to News").performClick()
        composeRule.onNodeWithContentDescription("Settings and account").performClick()
        composeRule.onNodeWithText("news.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("via auth.example.com").assertIsDisplayed()

        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.runOnIdle { assertEquals(1, signOuts) }
        // The session manager reports the sign-out.
        sessionState = SessionState.SignedOut(lastServerOrigin = "https://news.example.com")

        composeRule.onNodeWithText("Connect to your Newsfeed").assertIsDisplayed()
        composeRule.onNodeWithText("news.example.com").assertIsDisplayed()
        assertOnlyOnboarding()
    }

    @Test
    fun `a restored session starts on News, and Change server asks first`() {
        setApp(SessionState.SignedIn(session))
        composeRule.onNodeWithText(article.title).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Settings and account").performClick()
        composeRule.onNodeWithText("Change server").performClick()
        composeRule.onNode(hasText("Change server") and hasAnyAncestor(isDialog())).performClick()

        composeRule.runOnIdle { assertEquals(1, serverChanges) }
    }

    @Test
    fun `a session that ends on its own returns to onboarding and says why`() {
        setApp(SessionState.SignedIn(session))
        composeRule.onNodeWithText(article.title).performClick()
        composeRule.onNodeWithText("The saved body.").assertIsDisplayed()

        sessionState = SessionState.SignedOut(lastServerOrigin = "https://news.example.com", endReason = SessionEndReason.Expired)

        composeRule.onNodeWithText("Your sign-in has expired. Sign in again to keep reading.").assertIsDisplayed()
        assertOnlyOnboarding()
    }

    @Test
    fun `Settings opens the open-source licenses`() {
        setApp(SessionState.SignedIn(session))

        composeRule.onNodeWithContentDescription("Settings and account").performClick()
        composeRule.onNodeWithText("Open-source licenses").performScrollTo().performClick()

        composeRule.onNodeWithText("AppAuth for Android").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to Settings").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    private fun setApp(initialState: SessionState) {
        sessionState = initialState
        composeRule.setContent {
            navController = rememberNavController()
            NewsfeedApp(
                sessionState = sessionState,
                signInService = FakeSignInService(),
                onSignOut = { signOuts++ },
                onChangeServer = { serverChanges++ },
                navController = navController,
            )
        }
    }

    /** Onboarding is the only screen: the signed-in screens left the back stack. */
    private fun assertOnlyOnboarding() {
        composeRule.runOnIdle {
            assertTrue(navController.currentDestination?.hasRoute<Destination.Onboarding>() == true)
            assertNull(navController.previousBackStackEntry)
        }
    }
}
