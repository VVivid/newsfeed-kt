package dev.vvivid.newsfeed.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.testArticle
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ArticleScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an article in Trash offers Restore instead of Archive and Move to Trash`() {
        val actions = mutableListOf<ArticleAction>()
        setArticle(testArticle("trashed", trashedHoursAgo = 1), onAction = { actions += it })

        composeRule.onNodeWithContentDescription("Archive").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Move to Trash").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Share").assertIsDisplayed()
        composeRule.onNodeWithText("Restore").performClick()

        composeRule.runOnIdle { assertEquals(listOf(ArticleAction.Restore), actions) }
    }

    @Test
    fun `an Active article offers Archive and Move to Trash`() {
        setArticle(testArticle("active"))

        composeRule.onNodeWithContentDescription("Archive").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Move to Trash").assertIsDisplayed()
        composeRule.onNodeWithText("Restore").assertDoesNotExist()
    }

    @Test
    fun `Open original is labelled for accessibility services and reports the article address`() {
        val article = testArticle("original")
        val opened = mutableListOf<String>()
        setArticle(article, onOpenOriginal = { opened += it })

        composeRule.onNodeWithContentDescription("Open original").performClick()

        composeRule.runOnIdle { assertEquals(listOf(article.url), opened) }
    }

    @Test
    fun `a link in the body reports its address and opens nothing itself`() {
        val article = testArticle("linked")
        val openedLinks = mutableListOf<String>()
        setArticle(
            article,
            body = parseArticleBody("See [the notes](https://example.org/notes).", article.url.orEmpty()),
            onOpenLink = { openedLinks += it },
        )

        composeRule.onNodeWithText("See the notes.").performFirstLinkClick()

        composeRule.runOnIdle {
            assertEquals(listOf("https://example.org/notes"), openedLinks)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }

    @Test
    fun `an article without a saved body offers to open the original`() {
        val article = testArticle("summary-only")
        val opened = mutableListOf<String>()
        setArticle(article, body = emptyList(), onOpenOriginal = { opened += it })

        composeRule.onNodeWithText("The full article is not saved here. Open the original to read it.").assertIsDisplayed()
        composeRule.onNodeWithText("Open original").performClick()

        composeRule.runOnIdle { assertEquals(listOf(article.url), opened) }
    }

    private fun setArticle(
        article: Article,
        body: List<BodyBlock> = listOf(BodyBlock.Paragraph(BodyText("Saved body text."))),
        onAction: (ArticleAction) -> Unit = {},
        onOpenOriginal: (String) -> Unit = {},
        onOpenLink: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            NewsfeedTheme {
                ArticleScreen(
                    uiState = ArticleUiState.Loaded(article, body),
                    onBack = {},
                    onRetry = {},
                    onAction = onAction,
                    onShare = {},
                    onOpenOriginal = onOpenOriginal,
                    onOpenLink = onOpenLink,
                    onMessageShown = {},
                )
            }
        }
    }
}
