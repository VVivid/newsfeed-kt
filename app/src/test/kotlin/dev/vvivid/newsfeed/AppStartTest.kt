package dev.vvivid.newsfeed

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real app, with its real session storage, on a fresh install. */
@RunWith(RobolectricTestRunner::class)
class AppStartTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `without a saved session the app starts on onboarding`() {
        // The saved session is read in the background first.
        composeRule.waitUntilAtLeastOneExists(hasText("Connect to your Newsfeed"), timeoutMillis = 5_000)

        composeRule.onNodeWithText("Connect to your Newsfeed").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
    }
}
