package dev.vvivid.newsfeed.news

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.FakeNewsRepository
import dev.vvivid.newsfeed.data.TestNow
import dev.vvivid.newsfeed.data.testArticle
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class NewsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lead = testArticle("lead", savedHoursAgo = 1)
    private val earlier = testArticle("earlier", savedHoursAgo = 2)

    @Test
    fun `swiping a card left archives it and swiping right moves it to Trash`() {
        val archived = mutableListOf<Article>()
        val trashed = mutableListOf<Article>()
        setActiveShelf(onArchive = { archived += it }, onMoveToTrash = { trashed += it })

        composeRule.onNodeWithText(lead.title).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(earlier.title).performTouchInput { swipeRight() }

        composeRule.runOnIdle {
            assertEquals(listOf(lead), archived)
            assertEquals(listOf(earlier), trashed)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `accessibility actions archive a card or move it to Trash without swiping`() {
        val archived = mutableListOf<Article>()
        val trashed = mutableListOf<Article>()
        setActiveShelf(onArchive = { archived += it }, onMoveToTrash = { trashed += it })

        composeRule.onNodeWithText(earlier.title).performCustomAccessibilityActionWithLabel("Archive")
        composeRule.onNodeWithText(lead.title).performCustomAccessibilityActionWithLabel("Move to Trash")

        composeRule.runOnIdle {
            assertEquals(listOf(earlier), archived)
            assertEquals(listOf(lead), trashed)
        }
    }

    @Test
    fun `choosing a shelf shows its articles`() {
        val archived = testArticle("archived", archivedHoursAgo = 1)
        val trashed = testArticle("trashed", trashedHoursAgo = 1)
        setNewsRoute(FakeNewsRepository(listOf(lead, archived, trashed)))
        composeRule.onNodeWithText(lead.title).assertIsDisplayed()

        composeRule.onNodeWithText("Archive").performClick()
        composeRule.onNodeWithText(archived.title).assertIsDisplayed()
        composeRule.onNodeWithText(lead.title).assertDoesNotExist()

        composeRule.onNodeWithText("Trash").performClick()
        composeRule.onNodeWithText(trashed.title).assertIsDisplayed()
        composeRule
            .onNodeWithText("Items in Trash keep their content. Open one and tap Restore to bring it back.")
            .assertIsDisplayed()
    }

    @Test
    fun `a failed load offers Retry, and an empty shelf says so`() {
        val repository = FakeNewsRepository().apply { failure = IOException("offline") }
        setNewsRoute(repository)
        composeRule.onNodeWithText("Couldn't load articles.").assertIsDisplayed()

        repository.failure = null
        composeRule.onNodeWithText("Retry").performClick()

        composeRule.onNodeWithText("No articles yet").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load articles.").assertDoesNotExist()
    }

    @Test
    fun `reaching the end of the loaded articles loads the next page`() {
        // The first page holds only the lead and the earlier article.
        val third = testArticle("third", savedHoursAgo = 3)
        setNewsRoute(FakeNewsRepository(listOf(lead, earlier, third), pageSize = 2))

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(third.title))

        composeRule.onNodeWithText(third.title).assertIsDisplayed()
    }

    private fun setActiveShelf(
        onArchive: (Article) -> Unit,
        onMoveToTrash: (Article) -> Unit,
    ) {
        composeRule.setContent {
            NewsfeedTheme {
                NewsScreen(
                    uiState = NewsUiState(content = ShelfContent.Loaded(listOf(lead, earlier))),
                    now = TestNow,
                    onShelfSelect = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onArticleClick = {},
                    onArchive = onArchive,
                    onMoveToTrash = onMoveToTrash,
                    onUndo = {},
                    onMessageShown = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private fun setNewsRoute(repository: FakeNewsRepository) {
        val viewModel = NewsViewModel(repository)
        composeRule.setContent {
            NewsfeedTheme {
                NewsRoute(viewModel = viewModel, onOpenArticle = {}, onOpenSettings = {})
            }
        }
    }
}
