package dev.vvivid.newsfeed.reader

import dev.vvivid.newsfeed.MainDispatcherRule
import dev.vvivid.newsfeed.audio.ArticleAudio
import dev.vvivid.newsfeed.audio.ArticlePlayer
import dev.vvivid.newsfeed.audio.AudioConflictException
import dev.vvivid.newsfeed.audio.AudioRepository
import dev.vvivid.newsfeed.audio.AudioSegment
import dev.vvivid.newsfeed.audio.AudioStatus
import dev.vvivid.newsfeed.audio.AudioUnavailableException
import dev.vvivid.newsfeed.audio.AudioVoice
import dev.vvivid.newsfeed.audio.AudioVoiceName
import dev.vvivid.newsfeed.audio.FakeArticlePlayer
import dev.vvivid.newsfeed.audio.PlayerState
import dev.vvivid.newsfeed.audio.PlaylistPart
import dev.vvivid.newsfeed.audio.SpeechSegment
import dev.vvivid.newsfeed.audio.playlistIdOf
import dev.vvivid.newsfeed.data.testArticle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleAudioViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val article = testArticle("A1", revision = 4)
    private val body = parseArticleBody("## Section\n\nFirst sentence. Second sentence.", "")
    private val voices = listOf(AudioVoice("talia", "Talia", "Calm"))
    private val repository = FakeAudioRepository(voices)
    private val player = FakeArticlePlayer()
    private val watcher = RecordingWatcher()

    private fun viewModel() =
        ArticleAudioViewModel(
            articleId = "A1",
            repository = repository,
            newPlayer = { player },
            watcher = watcher,
            settings =
                object : AudioSettings {
                    override var speed = 1.25f
                },
        )

    @Test
    fun `without audio the reader offers to create it, with the cost`() =
        runTest {
            val viewModel = viewModel()
            viewModel.bind(article, body)
            advanceUntilIdle()

            val expectedCharacters = speechCharacters(speechParts(article.title, article.summary, body))
            assertEquals(AudioUiState.NotCreated(voices, expectedCharacters), viewModel.uiState.value)
        }

    @Test
    fun `a server without audio shows none`() =
        runTest {
            repository.unavailable = true
            val viewModel = viewModel()
            viewModel.bind(article, body)
            advanceUntilIdle()

            assertEquals(AudioUiState.Unavailable, viewModel.uiState.value)
        }

    @Test
    fun `creating sends the reader's parts, follows the job, then downloads and plays`() =
        runTest {
            val viewModel = viewModel()
            viewModel.bind(article, body)
            viewModel.setVisible(true)
            advanceUntilIdle()

            viewModel.create("talia")
            runCurrent()

            assertEquals(4L, repository.created?.second)
            assertEquals(speechParts(article.title, article.summary, body).map { it.segment }, repository.created?.third)
            assertEquals(listOf("watch A1"), watcher.calls)
            assertTrue(viewModel.uiState.value is AudioUiState.Generating)

            repository.audio = ready()
            advanceTimeBy(3_500)
            runCurrent()

            val state = viewModel.uiState.value as AudioUiState.Ready
            assertEquals(3, state.files.size)

            val collector = backgroundScope.launch { viewModel.listenState.collect {} }
            viewModel.listen()
            runCurrent()
            assertEquals(playlistIdOf("A1", 1_000), player.state.value.playlistId)
            assertEquals(1.25f, player.state.value.speed)

            // Halfway through the paragraph's second sentence.
            player.move(segmentIndex = 2, positionMs = 1_700)
            runCurrent()
            val listen = viewModel.listenState.value
            assertEquals(SpokenHighlight(block = 1, sentence = 16..31), listen.highlight)
            assertEquals("Section", listen.section)
            collector.cancel()
        }

    @Test
    fun `the sections and the skip buttons move across parts`() =
        runTest {
            repository.audio = ready()
            val viewModel = viewModel()
            viewModel.bind(article, body)
            advanceUntilIdle()
            val collector = backgroundScope.launch { viewModel.listenState.collect {} }
            viewModel.listen()
            runCurrent()
            // The home screen's player shows progress and the section from these.
            assertEquals(
                listOf(PlaylistPart(1_000, null), PlaylistPart(1_000, "Section"), PlaylistPart(3_000, "Section")),
                player.lastParts,
            )

            viewModel.skip(1_500)
            assertEquals(1 to 500L, player.lastSeek)

            player.move(segmentIndex = 2, positionMs = 100)
            viewModel.previousSection()
            assertEquals(1 to 0L, player.lastSeek)

            player.move(segmentIndex = 0, positionMs = 100)
            viewModel.nextSection()
            assertEquals(1 to 0L, player.lastSeek)
            collector.cancel()
        }

    @Test
    fun `deleting stops playback and unlocks the article`() =
        runTest {
            repository.audio = ready()
            val viewModel = viewModel()
            viewModel.bind(article, body)
            advanceUntilIdle()
            viewModel.listen()

            viewModel.delete()
            advanceUntilIdle()

            assertEquals(listOf(playlistIdOf("A1", 1_000)), player.stopped)
            assertEquals(1, repository.deletes)
            assertTrue(viewModel.uiState.value is AudioUiState.NotCreated)
            assertEquals(AudioMessage.Deleted, viewModel.message.value)
            assertEquals(listOf("stop A1"), watcher.calls)
        }

    @Test
    fun `moving the article to Trash drops its audio`() =
        runTest {
            repository.audio = ready()
            val viewModel = viewModel()
            viewModel.bind(article, body)
            advanceUntilIdle()

            viewModel.bind(testArticle("A1", revision = 5, trashedHoursAgo = 1), body)
            advanceUntilIdle()

            assertEquals(AudioUiState.Unavailable, viewModel.uiState.value)
            assertEquals(1, repository.downloadDeletes)
        }

    @Test
    fun `a changed article reloads instead of creating`() =
        runTest {
            val viewModel = viewModel()
            viewModel.bind(article, body)
            advanceUntilIdle()
            repository.createFailure = AudioConflictException("changed")

            viewModel.create("talia")
            advanceUntilIdle()

            assertEquals(AudioMessage.ChangedOnServer, viewModel.message.value)
            assertTrue(viewModel.uiState.value is AudioUiState.NotCreated)
        }

    /** Three parts: the header, the heading and the paragraph, each a second or two long. */
    private fun ready(): ArticleAudio {
        val parts = speechParts(article.title, article.summary, body)
        val paragraph = parts[2].segment.text
        return ArticleAudio(
            status = AudioStatus.Ready,
            voice = AudioVoiceName("talia", "Talia"),
            createdAt = Instant.ofEpochMilli(1_000),
            characters = speechCharacters(parts),
            segmentsTotal = 3,
            segmentsDone = 3,
            error = null,
            durationMs = 5_000,
            segments =
                listOf(
                    AudioSegment(0, parts[0].segment.block, parts[0].segment.text, 1_000, emptyList()),
                    AudioSegment(1, parts[1].segment.block, parts[1].segment.text, 1_000, emptyList()),
                    // Each character takes 100 ms.
                    AudioSegment(2, parts[2].segment.block, paragraph, 3_000, List(paragraph.length) { it * 100 }),
                ),
        )
    }
}

private class FakeAudioRepository(
    private val voices: List<AudioVoice>,
) : AudioRepository {
    var unavailable = false
    var audio: ArticleAudio? = null
    var created: Triple<String, Long, List<SpeechSegment>>? = null
    var createFailure: Exception? = null
    var deletes = 0
    var downloadDeletes = 0

    override suspend fun voices(): List<AudioVoice> = if (unavailable) throw AudioUnavailableException() else voices

    override suspend fun sample(voiceId: String): File = File("sample.mp3")

    override suspend fun audio(articleId: String): ArticleAudio? = audio

    override suspend fun create(
        articleId: String,
        voiceId: String,
        expectedRevision: Long,
        segments: List<SpeechSegment>,
    ): ArticleAudio {
        createFailure?.let { throw it }
        created = Triple(voiceId, expectedRevision, segments)
        val generating =
            ArticleAudio(
                AudioStatus.Generating,
                AudioVoiceName(voiceId, "Talia"),
                Instant.ofEpochMilli(1_000),
                10,
                segments.size,
                0,
                null,
                0,
                emptyList(),
            )
        audio = generating
        return generating
    }

    override suspend fun delete(articleId: String) {
        deletes++
        audio = null
    }

    override suspend fun download(
        articleId: String,
        audio: ArticleAudio,
        onProgress: (filesPresent: Int) -> Unit,
    ): List<File> = audio.segments.map { File("${it.index}.mp3") }

    override suspend fun deleteDownloads(articleId: String) {
        downloadDeletes++
    }
}

private class RecordingWatcher : AudioJobWatcher {
    val calls = mutableListOf<String>()

    override fun watch(
        articleId: String,
        title: String,
    ) {
        calls += "watch $articleId"
    }

    override fun stop(articleId: String) {
        calls += "stop $articleId"
    }
}
