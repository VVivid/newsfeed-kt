package dev.vvivid.newsfeed.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import dev.vvivid.newsfeed.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var serverChanges = 0
    private var signOuts = 0
    private var licenseOpenings = 0
    private val themeChoices = mutableListOf<ThemeMode>()

    @Test
    fun `the account shows where the user signed in, and Sign out reports it`() {
        setSettings()

        composeRule.onNodeWithText("via auth.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").performClick()

        composeRule.runOnIdle { assertEquals(1, signOuts) }
    }

    @Test
    fun `About opens the open-source licenses`() {
        setSettings()

        composeRule.onNodeWithText("Open-source licenses").performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(1, licenseOpenings) }
    }

    @Test
    fun `changing server asks for confirmation first`() {
        setSettings()
        composeRule.onNodeWithText("news.example.com").assertIsDisplayed()

        composeRule.onNodeWithText("Change server").performClick()
        composeRule.onNodeWithText("Change server?").assertIsDisplayed()
        composeRule
            .onNodeWithText("You will be signed out of news.example.com.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Change server?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, serverChanges) }

        composeRule.onNodeWithText("Change server").performClick()
        composeRule.onNode(hasText("Change server") and hasAnyAncestor(isDialog())).performClick()

        composeRule.onNodeWithText("Change server?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, serverChanges) }
    }

    @Test
    fun `choosing a theme reports it`() {
        setSettings()

        composeRule.onNodeWithText("Dark").performClick()

        composeRule.runOnIdle { assertEquals(listOf(ThemeMode.Dark), themeChoices) }
    }

    private fun setSettings() {
        composeRule.setContent {
            NewsfeedTheme {
                SettingsRoute(
                    serverOrigin = "https://news.example.com",
                    signInHost = "auth.example.com",
                    themeMode = ThemeMode.System,
                    onThemeModeChange = { themeChoices += it },
                    useDynamicColor = false,
                    onDynamicColorChange = {},
                    onBack = {},
                    onSignOut = { signOuts++ },
                    onChangeServer = { serverChanges++ },
                    onOpenLicenses = { licenseOpenings++ },
                )
            }
        }
    }
}
