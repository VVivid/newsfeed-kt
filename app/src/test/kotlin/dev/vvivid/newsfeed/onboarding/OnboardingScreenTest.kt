package dev.vvivid.newsfeed.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.vvivid.newsfeed.auth.DiscoveryProblem
import dev.vvivid.newsfeed.auth.SessionEndReason
import dev.vvivid.newsfeed.auth.SignInProblem
import dev.vvivid.newsfeed.auth.testServer
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var signIns = 0
    private var differentServers = 0

    @Test
    fun `Server found shows the server, its sign-in page and the access asked for`() {
        setScreen(OnboardingUiState(serverAddress = "news.example.com", step = OnboardingStep.ConfirmServer(testServer())))

        composeRule.onNodeWithText("Server found").assertIsDisplayed()
        composeRule.onNodeWithText("https://news.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("auth.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Read articles, archive and trash, and stay signed in").assertIsDisplayed()

        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithText("Use a different server").performClick()

        composeRule.runOnIdle {
            assertEquals(1, signIns)
            assertEquals(1, differentServers)
        }
    }

    @Test
    fun `a sign-in problem is shown and finishing a sign-in cannot be interrupted`() {
        setScreen(
            OnboardingUiState(
                step = OnboardingStep.ConfirmServer(testServer(), isFinishingSignIn = true, problem = SignInProblem.AccessDenied),
            ),
        )

        composeRule
            .onNodeWithText(
                "The app wasn't allowed access. To read your newsfeed, sign in again and allow access.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Signing in").assertIsNotEnabled()
        composeRule.onNodeWithText("Use a different server").assertIsNotEnabled()
    }

    @Test
    fun `a server that cannot be used is named, and an ended session is explained`() {
        setScreen(
            OnboardingUiState(
                serverAddress = "news.example.com",
                step = OnboardingStep.EnterAddress(AddressFeedback.ServerNotUsable(DiscoveryProblem.Unreachable, "news.example.com")),
                sessionEndReason = SessionEndReason.Expired,
            ),
        )

        composeRule.onNodeWithText("Couldn't reach news.example.com. Check the address and your internet connection.").assertIsDisplayed()
        composeRule.onNodeWithText("Your sign-in has expired. Sign in again to keep reading.").assertIsDisplayed()
    }

    @Test
    fun `Continue shows that the server is being checked`() {
        setScreen(OnboardingUiState(serverAddress = "news.example.com", step = OnboardingStep.EnterAddress(isChecking = true)))

        composeRule.onNodeWithText("Checking the server").assertIsNotEnabled()
    }

    private fun setScreen(uiState: OnboardingUiState) {
        composeRule.setContent {
            NewsfeedTheme {
                OnboardingScreen(
                    uiState = uiState,
                    onServerAddressChange = {},
                    onContinue = {},
                    onSignIn = { signIns++ },
                    onUseDifferentServer = { differentServers++ },
                )
            }
        }
    }
}
