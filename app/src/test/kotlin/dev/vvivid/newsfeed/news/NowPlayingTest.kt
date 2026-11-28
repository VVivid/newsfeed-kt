package dev.vvivid.newsfeed.news

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.vvivid.newsfeed.audio.FakeArticlePlayer
import dev.vvivid.newsfeed.audio.PlayerState
import dev.vvivid.newsfeed.audio.playlistIdOf
import dev.vvivid.newsfeed.data.TestNow
import dev.vvivid.newsfeed.data.testArticle
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NowPlayingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lead = testArticle("lead", savedHoursAgo = 1)
    private val other = testArticle("other", savedHoursAgo = 2)

    private val playing =
        PlayerState(
            playlistId = playlistIdOf(lead.id, 1_795_000_000_000),
            isPlaying = true,
            title = lead.title,
            voiceName = "Talia",
            elapsedMs = 501_000,
            totalMs = 873_000,
            section = "A cascade, not a score",
        )

    @Test
    fun `the player state becomes what the home screen shows`() {
        assertEquals(
            NowPlaying(lead.id, lead.title, "A cascade, not a score", isPlaying = true, elapsedMs = 501_000, totalMs = 873_000),
            nowPlayingOf(playing),
        )
        assertNull(nowPlayingOf(PlayerState()))
        // Played to the end and stopped: nothing is left to control.
        assertNull(nowPlayingOf(playing.copy(isPlaying = false, elapsedMs = 873_000)))
        // Paused partway, it stays.
        assertEquals(false, nowPlayingOf(playing.copy(isPlaying = false))?.isPlaying)
    }

    @Test
    fun `play, pause and skipping go to the player`() {
        val player = FakeArticlePlayer()
        player.show(playing)
        val viewModel = NowPlayingViewModel { player }

        viewModel.toggle()
        assertFalse(player.state.value.isPlaying)
        viewModel.toggle()
        assertTrue(player.state.value.isPlaying)
        viewModel.skip(-15_000)
        viewModel.skip(15_000)

        assertEquals(listOf(-15_000L, 15_000L), player.skips)
    }

    @Test
    fun `the home screen shows the player and marks the article being read`() {
        val opened = mutableListOf<String>()
        val skips = mutableListOf<Long>()
        var toggles = 0
        composeRule.setContent {
            NewsfeedTheme {
                NewsScreen(
                    uiState = NewsUiState(content = ShelfContent.Loaded(listOf(lead, other))),
                    now = TestNow,
                    onShelfSelect = {},
                    onTagToggle = {},
                    onClearTags = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onArticleClick = {},
                    onArchive = {},
                    onMoveToTrash = {},
                    onUndo = {},
                    onMessageShown = {},
                    onOpenSettings = {},
                    nowPlaying = nowPlayingOf(playing),
                    nowPlayingActions = NowPlayingActions(onToggle = { toggles++ }, onSkip = { skips += it }, onOpen = { opened += it }),
                )
            }
        }

        composeRule.onNodeWithText("A cascade, not a score · 8:21 / 14:33").assertIsDisplayed()
        composeRule.onNodeWithText("Playing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back 15 seconds").performClick()
        composeRule.onNodeWithContentDescription("Forward 15 seconds").performClick()
        composeRule.onNodeWithContentDescription("Pause").performClick()
        composeRule.onNodeWithText("A cascade, not a score · 8:21 / 14:33").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(-15_000L, 15_000L), skips)
            assertEquals(1, toggles)
            assertEquals(listOf(lead.id), opened)
        }
    }

    @Test
    fun `nothing loaded, no player`() {
        composeRule.setContent {
            NewsfeedTheme {
                NewsScreen(
                    uiState = NewsUiState(content = ShelfContent.Loaded(listOf(lead))),
                    now = TestNow,
                    onShelfSelect = {},
                    onTagToggle = {},
                    onClearTags = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onArticleClick = {},
                    onArchive = {},
                    onMoveToTrash = {},
                    onUndo = {},
                    onMessageShown = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Back 15 seconds").assertDoesNotExist()
        composeRule.onNodeWithText("Playing").assertDoesNotExist()
    }
}
