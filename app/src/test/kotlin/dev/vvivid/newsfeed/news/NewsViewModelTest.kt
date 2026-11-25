package dev.vvivid.newsfeed.news

import dev.vvivid.newsfeed.MainDispatcherRule
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.FakeNewsRepository
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.data.testArticle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NewsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val newest = testArticle("newest", savedHoursAgo = 1)
    private val older = testArticle("older", savedHoursAgo = 5)
    private val archived = testArticle("archived", savedHoursAgo = 30, archivedHoursAgo = 20)
    private val trashed = testArticle("trashed", savedHoursAgo = 40, trashedHoursAgo = 10)
    private val repository = FakeNewsRepository(listOf(older, newest, archived, trashed))

    @Test
    fun `opens on the Active shelf, newest first`() =
        runTest {
            val viewModel = NewsViewModel(repository)
            assertEquals(NewsUiState(shelf = Shelf.Active, content = ShelfContent.Loading), viewModel.uiState.value)

            advanceUntilIdle()

            assertEquals(ShelfContent.Loaded(listOf(newest, older)), viewModel.uiState.value.content)
        }

    @Test
    fun `a failed first load shows the failure, and refresh loads again`() =
        runTest {
            repository.failure = IOException("offline")
            val viewModel = NewsViewModel(repository)
            advanceUntilIdle()
            assertEquals(ShelfContent.LoadFailed, viewModel.uiState.value.content)

            repository.failure = null
            viewModel.refresh()
            assertEquals(ShelfContent.Loading, viewModel.uiState.value.content)
            advanceUntilIdle()

            assertEquals(ShelfContent.Loaded(listOf(newest, older)), viewModel.uiState.value.content)
        }

    @Test
    fun `a failed refresh keeps the articles on screen and marks them stale`() =
        runTest {
            val viewModel = loadedViewModel()
            repository.failure = IOException("offline")

            viewModel.refresh()
            assertEquals(ShelfContent.Loaded(listOf(newest, older), isRefreshing = true), viewModel.uiState.value.content)
            advanceUntilIdle()
            assertEquals(ShelfContent.Loaded(listOf(newest, older), isStale = true), viewModel.uiState.value.content)

            repository.failure = null
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(ShelfContent.Loaded(listOf(newest, older)), viewModel.uiState.value.content)
        }

    @Test
    fun `refresh does nothing while a load is running`() =
        runTest {
            repository.hold(Shelf.Active)
            val viewModel = NewsViewModel(repository)
            advanceUntilIdle()

            viewModel.refresh()
            repository.release(Shelf.Active)
            advanceUntilIdle()

            assertEquals(listOf(Shelf.Active), repository.listedShelves)
            assertEquals(ShelfContent.Loaded(listOf(newest, older)), viewModel.uiState.value.content)
        }

    @Test
    fun `selecting another shelf supersedes a slow load of the previous one`() =
        runTest {
            repository.hold(Shelf.Active)
            val viewModel = NewsViewModel(repository)
            advanceUntilIdle()

            viewModel.selectShelf(Shelf.Archive)
            assertEquals(NewsUiState(shelf = Shelf.Archive, content = ShelfContent.Loading), viewModel.uiState.value)
            advanceUntilIdle()
            repository.release(Shelf.Active)
            advanceUntilIdle()

            assertEquals(Shelf.Archive, viewModel.uiState.value.shelf)
            assertEquals(ShelfContent.Loaded(listOf(archived)), viewModel.uiState.value.content)
        }

    @Test
    fun `archiving removes the article at once, then offers to undo it`() =
        runTest {
            val viewModel = loadedViewModel()

            viewModel.archive(newest)
            assertEquals(listOf(older), viewModel.articles())
            advanceUntilIdle()

            val archivedNow = repository.article("newest")
            assertEquals(Shelf.Archive, archivedNow.shelf)
            assertEquals(listOf(older), viewModel.articles())
            assertEquals(NewsMessage.Moved(archivedNow, ArticleMove.Archive), viewModel.uiState.value.message)
        }

    @Test
    fun `undo returns an archived article to its place on Active`() =
        runTest {
            val viewModel = loadedViewModel()
            viewModel.archive(newest)
            advanceUntilIdle()

            viewModel.undo(viewModel.uiState.value.message as NewsMessage.Moved)
            assertNull(viewModel.uiState.value.message)
            advanceUntilIdle()

            val restored = repository.article("newest")
            assertEquals(Shelf.Active, restored.shelf)
            assertEquals(listOf(restored, older), viewModel.articles())
        }

    @Test
    fun `undo takes an article out of Trash again`() =
        runTest {
            val viewModel = loadedViewModel()
            viewModel.moveToTrash(older)
            advanceUntilIdle()
            val moved = viewModel.uiState.value.message as NewsMessage.Moved
            assertEquals(ArticleMove.Trash, moved.move)
            assertEquals(Shelf.Trash, repository.article("older").shelf)

            viewModel.undo(moved)
            advanceUntilIdle()

            assertEquals(Shelf.Active, repository.article("older").shelf)
            assertEquals(listOf(newest, repository.article("older")), viewModel.articles())
        }

    @Test
    fun `a rejected move puts the article back and says so`() =
        runTest {
            val viewModel = loadedViewModel()
            repository.failure = IOException("offline")

            viewModel.moveToTrash(newest)
            assertEquals(listOf(older), viewModel.articles())
            advanceUntilIdle()

            assertEquals(listOf(newest, older), viewModel.articles())
            assertEquals(NewsMessage.MoveFailed(ArticleMove.Trash), viewModel.uiState.value.message)
        }

    @Test
    fun `a failed undo leaves the article where the move put it and says so`() =
        runTest {
            val viewModel = loadedViewModel()
            viewModel.archive(newest)
            advanceUntilIdle()
            repository.failure = IOException("offline")

            viewModel.undo(viewModel.uiState.value.message as NewsMessage.Moved)
            advanceUntilIdle()

            assertEquals(listOf(older), viewModel.articles())
            assertEquals(NewsMessage.UndoFailed(ArticleMove.Archive), viewModel.uiState.value.message)
        }

    @Test
    fun `an undone article leaves the shown Archive list`() =
        runTest {
            val viewModel = loadedViewModel()
            viewModel.archive(newest)
            advanceUntilIdle()
            val moved = viewModel.uiState.value.message as NewsMessage.Moved
            viewModel.selectShelf(Shelf.Archive)
            advanceUntilIdle()
            assertEquals(listOf(repository.article("newest"), archived), viewModel.articles())

            viewModel.undo(moved)
            advanceUntilIdle()

            assertEquals(listOf(archived), viewModel.articles())
        }

    @Test
    fun `a closed snackbar clears its message but not a newer one`() =
        runTest {
            val viewModel = loadedViewModel()
            viewModel.archive(newest)
            advanceUntilIdle()
            val first = viewModel.uiState.value.message as NewsMessage.Moved
            viewModel.archive(older)
            advanceUntilIdle()
            val second = viewModel.uiState.value.message as NewsMessage.Moved

            viewModel.messageShown(first)
            assertEquals(second, viewModel.uiState.value.message)

            viewModel.messageShown(second)
            assertNull(viewModel.uiState.value.message)
        }

    private fun TestScope.loadedViewModel(): NewsViewModel {
        val viewModel = NewsViewModel(repository)
        advanceUntilIdle()
        return viewModel
    }

    private fun NewsViewModel.articles(): List<Article> = (uiState.value.content as ShelfContent.Loaded).articles
}
