package dev.vvivid.newsfeed.reader

import dev.vvivid.newsfeed.MainDispatcherRule
import dev.vvivid.newsfeed.data.FakeNewsRepository
import dev.vvivid.newsfeed.data.PermissionDeniedException
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.data.testArticle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val active = testArticle("active")
    private val trashed = testArticle("trashed", archivedHoursAgo = 2, trashedHoursAgo = 1)
    private val repository =
        FakeNewsRepository(
            articles = listOf(active, trashed),
            bodies = mapOf("active" to "A *saved* body."),
        )

    @Test
    fun `loads the article and its parsed body`() =
        runTest {
            val viewModel = articleViewModel("active", inTrash = false)
            assertEquals(ArticleUiState.Loading, viewModel.uiState.value)

            advanceUntilIdle()

            val expectedBody =
                listOf(BodyBlock.Paragraph(BodyText("A saved body.", listOf(StyledRange(InlineStyle.Emphasis, 2, 7)))))
            assertEquals(ArticleUiState.Loaded(active, expectedBody), viewModel.uiState.value)
        }

    @Test
    fun `an article saved without a body has an empty body`() =
        runTest {
            val viewModel = articleViewModel("trashed", inTrash = true)
            advanceUntilIdle()

            assertEquals(ArticleUiState.Loaded(trashed, body = emptyList()), viewModel.uiState.value)
        }

    @Test
    fun `an article that moved into Trash since it was opened is still found`() =
        runTest {
            // As after the reader moved it to Trash and Android restarted the app with the reader's
            // original arguments.
            val viewModel = articleViewModel("trashed", inTrash = false)
            advanceUntilIdle()

            assertEquals(trashed, viewModel.loaded().article)
        }

    @Test
    fun `an article that left Trash since it was opened is still found`() =
        runTest {
            val viewModel = articleViewModel("active", inTrash = true)
            advanceUntilIdle()

            assertEquals(active, viewModel.loaded().article)
        }

    @Test
    fun `an article that is nowhere fails to load`() =
        runTest {
            val viewModel = articleViewModel("missing", inTrash = false)
            advanceUntilIdle()

            assertEquals(ArticleUiState.LoadFailed, viewModel.uiState.value)
        }

    @Test
    fun `a failed load can be retried`() =
        runTest {
            repository.failure = IOException("offline")
            val viewModel = articleViewModel("active", inTrash = false)
            advanceUntilIdle()
            assertEquals(ArticleUiState.LoadFailed, viewModel.uiState.value)

            repository.failure = null
            viewModel.retry()
            assertEquals(ArticleUiState.Loading, viewModel.uiState.value)
            advanceUntilIdle()

            assertEquals(active, viewModel.loaded().article)
        }

    @Test
    fun `archiving shows the article as the server returns it`() =
        runTest {
            val viewModel = loadedViewModel("active", inTrash = false)

            viewModel.perform(ArticleAction.Archive)
            assertTrue(viewModel.loaded().isUpdating)
            advanceUntilIdle()

            val state = viewModel.loaded()
            assertEquals(repository.article("active"), state.article)
            assertEquals(Shelf.Archive, state.article.shelf)
            assertEquals(2L, state.article.revision)
            assertFalse(state.isUpdating)
            assertEquals(ArticleMessage.Done(ArticleAction.Archive), state.message)
        }

    @Test
    fun `restoring from Trash returns the article to where it was`() =
        runTest {
            val viewModel = loadedViewModel("trashed", inTrash = true)

            viewModel.perform(ArticleAction.Restore)
            advanceUntilIdle()

            assertEquals(Shelf.Archive, viewModel.loaded().article.shelf)
            assertEquals(ArticleMessage.Done(ArticleAction.Restore), viewModel.loaded().message)
        }

    @Test
    fun `a failed change keeps the article and says so`() =
        runTest {
            val viewModel = loadedViewModel("active", inTrash = false)
            repository.failure = IOException("offline")

            viewModel.perform(ArticleAction.MoveToTrash)
            advanceUntilIdle()

            val state = viewModel.loaded()
            assertEquals(active, state.article)
            assertFalse(state.isUpdating)
            assertEquals(ArticleMessage.Failed(ArticleAction.MoveToTrash), state.message)
        }

    @Test
    fun `a change refused because the article changed on the server shows the article as it is now`() =
        runTest {
            val viewModel = loadedViewModel("active", inTrash = false)
            // Archived in the web reader meanwhile: the reader's copy has a stale revision.
            val archivedElsewhere = active.copy(revision = 2, archivedAt = active.createdAt)
            repository.replace(archivedElsewhere)

            viewModel.perform(ArticleAction.MoveToTrash)
            advanceUntilIdle()

            val state = viewModel.loaded()
            assertEquals(archivedElsewhere, state.article)
            assertFalse(state.isUpdating)
            assertEquals(ArticleMessage.ChangedOnServer, state.message)
            assertEquals(Shelf.Archive, repository.article("active").shelf)
        }

    @Test
    fun `a change the sign-in does not allow keeps the article and says so`() =
        runTest {
            val viewModel = loadedViewModel("active", inTrash = false)
            repository.failure = PermissionDeniedException()

            viewModel.perform(ArticleAction.Archive)
            advanceUntilIdle()

            assertEquals(active, viewModel.loaded().article)
            assertEquals(ArticleMessage.ChangeNotAllowed, viewModel.loaded().message)
        }

    @Test
    fun `a second change waits for the first, and changes the shelf does not offer are ignored`() =
        runTest {
            val viewModel = loadedViewModel("active", inTrash = false)

            viewModel.perform(ArticleAction.Archive)
            viewModel.perform(ArticleAction.MoveToTrash)
            advanceUntilIdle()
            assertEquals(Shelf.Archive, repository.article("active").shelf)

            viewModel.perform(ArticleAction.Restore)
            advanceUntilIdle()
            assertEquals(Shelf.Archive, repository.article("active").shelf)
            assertEquals(ArticleMessage.Done(ArticleAction.Archive), viewModel.loaded().message)
        }

    @Test
    fun `Trash offers only Restore`() {
        assertEquals(listOf(ArticleAction.Archive, ArticleAction.MoveToTrash), articleActions(Shelf.Active))
        assertEquals(listOf(ArticleAction.Unarchive, ArticleAction.MoveToTrash), articleActions(Shelf.Archive))
        assertEquals(listOf(ArticleAction.Restore), articleActions(Shelf.Trash))
    }

    private fun articleViewModel(
        id: String,
        inTrash: Boolean,
    ) = ArticleViewModel(id, inTrash, repository, parsingDispatcher = mainDispatcherRule.dispatcher)

    private fun TestScope.loadedViewModel(
        id: String,
        inTrash: Boolean,
    ): ArticleViewModel {
        val viewModel = articleViewModel(id, inTrash)
        advanceUntilIdle()
        return viewModel
    }

    private fun ArticleViewModel.loaded(): ArticleUiState.Loaded = uiState.value as ArticleUiState.Loaded
}
