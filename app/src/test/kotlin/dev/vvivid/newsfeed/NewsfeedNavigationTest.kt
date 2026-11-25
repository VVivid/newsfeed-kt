package dev.vvivid.newsfeed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.vvivid.newsfeed.data.FakeNewsRepository
import dev.vvivid.newsfeed.data.testArticle
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The signed-in screens, reached the way sign-in will reach them once it exists. */
@RunWith(RobolectricTestRunner::class)
class NewsfeedNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val article = testArticle("navigation")
    private val repository = FakeNewsRepository(listOf(article), bodies = mapOf(article.id to "The saved body."))
    private lateinit var navController: NavHostController

    @Test
    fun `News opens the article and Settings, and changing server returns to onboarding`() {
        composeRule.setContent {
            navController = rememberNavController()
            NewsfeedApp(
                session = SignedInSession("https://news.example.com", repository),
                navController = navController,
            )
        }
        composeRule.onNodeWithText("Connect to your Newsfeed").assertIsDisplayed()
        composeRule.runOnIdle {
            navController.navigate(Destination.News) { popUpTo(Destination.Onboarding) { inclusive = true } }
        }

        composeRule.onNodeWithText(article.title).performClick()
        composeRule.onNodeWithText("The saved body.").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back to News").performClick()
        composeRule.onNodeWithContentDescription("Settings and account").performClick()
        composeRule.onNodeWithText("news.example.com").assertIsDisplayed()

        composeRule.onNodeWithText("Change server").performClick()
        composeRule.onNode(hasText("Change server") and hasAnyAncestor(isDialog())).performClick()

        composeRule.onNodeWithText("Connect to your Newsfeed").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(navController.currentDestination?.hasRoute<Destination.Onboarding>() == true)
            // Nothing to go back to: the signed-in screens left the back stack.
            assertNull(navController.previousBackStackEntry)
        }
    }
}
