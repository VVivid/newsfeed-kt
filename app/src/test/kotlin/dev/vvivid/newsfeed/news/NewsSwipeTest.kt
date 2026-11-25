package dev.vvivid.newsfeed.news

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpRect
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.FakeNewsRepository
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.data.testArticle
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Swipes through the whole News screen, with the real view model, so that swiped cards leave the
 * list and come back as they do in the app: after Undo, or when the server rejects the move.
 */
@RunWith(RobolectricTestRunner::class)
class NewsSwipeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lead = testArticle("lead", savedHoursAgo = 1)
    private val earlier = testArticle("earlier", savedHoursAgo = 2)
    private val repository = FakeNewsRepository(listOf(lead, earlier))

    @Test
    fun `undoing an archive brings the card back in place, and it stays`() {
        setNewsRoute()
        val place = boundsOf(lead)

        card(lead).performTouchInput { swipeLeft() }
        card(lead).assertDoesNotExist()
        composeRule.onNodeWithText("Article archived").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()

        assertEquals(place, boundsOf(lead))
        composeRule.onNodeWithText("Article archived").assertDoesNotExist()
        afterSnackbarsClose()
        assertEquals(place, boundsOf(lead))
        composeRule.runOnIdle {
            // The archive and its undo; a third write would be a move nobody asked for.
            assertEquals(2, repository.writeCount)
            assertEquals(lead.revision + 2, repository.article(lead.id).revision)
            assertEquals(Shelf.Active, repository.article(lead.id).shelf)
        }
    }

    @Test
    fun `undoing a move to Trash brings the card back in place, and it stays`() {
        setNewsRoute()
        val place = boundsOf(earlier)

        card(earlier).performTouchInput { swipeRight() }
        card(earlier).assertDoesNotExist()
        composeRule.onNodeWithText("Undo").performClick()

        assertEquals(place, boundsOf(earlier))
        composeRule.onNodeWithText("Article moved to Trash").assertDoesNotExist()
        afterSnackbarsClose()
        assertEquals(place, boundsOf(earlier))
        composeRule.runOnIdle {
            assertEquals(2, repository.writeCount)
            assertEquals(earlier.revision + 2, repository.article(earlier.id).revision)
            assertEquals(Shelf.Active, repository.article(earlier.id).shelf)
        }
    }

    @Test
    fun `a move the server rejects at once leaves the card in place after one write`() {
        setNewsRoute()
        val place = boundsOf(earlier)
        repository.failure = IOException("offline")

        card(earlier).performTouchInput { swipeRight() }

        composeRule.onNodeWithText("Couldn't move the article to Trash. Try again.").assertIsDisplayed()
        assertEquals(place, boundsOf(earlier))
        afterSnackbarsClose()
        assertEquals(place, boundsOf(earlier))
        composeRule.runOnIdle { assertEquals(1, repository.writeCount) }
    }

    @Test
    fun `a slow move that fails brings back the card that already left, after one write`() {
        setNewsRoute()
        val place = boundsOf(lead)
        repository.holdWrites()

        card(lead).performTouchInput { swipeLeft() }
        card(lead).assertDoesNotExist()
        repository.failure = IOException("offline")
        composeRule.runOnIdle { repository.releaseWrites() }

        composeRule.onNodeWithText("Couldn't archive the article. Try again.").assertIsDisplayed()
        assertEquals(place, boundsOf(lead))
        afterSnackbarsClose()
        assertEquals(place, boundsOf(lead))
        composeRule.runOnIdle { assertEquals(1, repository.writeCount) }
    }

    private fun setNewsRoute() {
        val viewModel = NewsViewModel(repository)
        composeRule.setContent {
            NewsfeedTheme {
                NewsRoute(viewModel = viewModel, onOpenArticle = {}, onOpenSettings = {})
            }
        }
    }

    /** Lets every snackbar time out, so the screen recomposes once more without a message. */
    private fun afterSnackbarsClose() {
        composeRule.mainClock.advanceTimeBy(SNACKBAR_LONG_MILLIS)
        composeRule.waitForIdle()
    }

    /** The article's card: TalkBack's merged node, carrying the card's bounds. */
    private fun card(article: Article): SemanticsNodeInteraction = composeRule.onNodeWithText(article.title)

    private fun boundsOf(article: Article): DpRect = card(article).assertIsDisplayed().getUnclippedBoundsInRoot()
}

/** Material's long snackbar duration, the longest a message stays on screen without TalkBack. */
private const val SNACKBAR_LONG_MILLIS = 10_000L
