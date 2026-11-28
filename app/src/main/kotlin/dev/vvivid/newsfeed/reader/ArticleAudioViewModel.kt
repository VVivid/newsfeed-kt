package dev.vvivid.newsfeed.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvivid.newsfeed.audio.ArticleAudio
import dev.vvivid.newsfeed.audio.ArticlePlayer
import dev.vvivid.newsfeed.audio.AudioConflictException
import dev.vvivid.newsfeed.audio.AudioRepository
import dev.vvivid.newsfeed.audio.AudioRequestException
import dev.vvivid.newsfeed.audio.AudioSegment
import dev.vvivid.newsfeed.audio.AudioStatus
import dev.vvivid.newsfeed.audio.AudioUnavailableException
import dev.vvivid.newsfeed.audio.AudioVoice
import dev.vvivid.newsfeed.audio.HEADER_BLOCK
import dev.vvivid.newsfeed.audio.PlayerState
import dev.vvivid.newsfeed.audio.PlaylistPart
import dev.vvivid.newsfeed.audio.SpeechSegment
import dev.vvivid.newsfeed.audio.playlistIdOf
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.NarrationPart
import dev.vvivid.newsfeed.data.Shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Clock

/** The audio part of the reader. */
sealed interface AudioUiState {
    /** The server makes no audio, or the article cannot have any (it is in Trash): nothing shows. */
    data object Unavailable : AudioUiState

    /** Finding out whether the article has audio. */
    data object Checking : AudioUiState

    /** Checking failed, for example offline. Shows nothing; opening the reader again checks again. */
    data object CheckFailed : AudioUiState

    /** No audio yet: the reader offers to create it. */
    data class NotCreated(
        val voices: List<AudioVoice>,
        val characters: Int,
        val isCreating: Boolean = false,
    ) : AudioUiState

    /** The server is making the audio. */
    data class Generating(
        val audio: ArticleAudio,
        val isCancelling: Boolean = false,
    ) : AudioUiState

    /** The server gave up making the audio; [ArticleAudio.error] says why. */
    data class Failed(
        val audio: ArticleAudio,
        val isRetrying: Boolean = false,
    ) : AudioUiState

    /** The audio is ready on the server and coming to this device. */
    data class Downloading(
        val audio: ArticleAudio,
        val filesPresent: Int,
    ) : AudioUiState

    /** The audio is on this device and can play. */
    data class Ready(
        val audio: ArticleAudio,
        val files: List<File>,
        val isDeleting: Boolean = false,
    ) : AudioUiState
}

/** Whether the article has audio, which locks its text and makes moving it to Trash ask first. */
val AudioUiState.hasAudio: Boolean
    get() = this is AudioUiState.Generating || this is AudioUiState.Failed || this is AudioUiState.Downloading || this is AudioUiState.Ready

/** A snackbar message about audio. */
sealed interface AudioMessage {
    /** Creating failed; [reason] is the server's explanation, if it gave one. */
    data class CreateFailed(
        val reason: String?,
    ) : AudioMessage

    /** The article or its audio changed on the server; the reader shows the current state. */
    data object ChangedOnServer : AudioMessage

    data object DownloadFailed : AudioMessage

    data object DeleteFailed : AudioMessage

    data object Deleted : AudioMessage
}

/**
 * Playback of this article's audio.
 *
 * @property isLoaded the player holds this article's audio, so the mini player shows.
 * @property positionMs the position in the whole audio.
 * @property highlight what the voice is reading in the body; null above the body.
 * @property sleepAtMillis when the sleep timer pauses playback, or null.
 * @property upNext the next section headings.
 */
data class ListenState(
    val isLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val section: String? = null,
    val highlight: SpokenHighlight? = null,
    val sleepAtMillis: Long? = null,
    val upNext: List<String> = emptyList(),
)

/**
 * The part of the body being read: block [block] of the reader body and, when the block is read
 * word for word, the current sentence as a range of the block's text.
 */
data class SpokenHighlight(
    val block: Int,
    val sentence: IntRange?,
)

/** Remembers the listening speed the user chose last. */
interface AudioSettings {
    var speed: Float
}

/** Follows an audio job in the background and says when it is ready (see AudioReadyWorker). */
interface AudioJobWatcher {
    fun watch(
        articleId: String,
        title: String,
    )

    fun stop(articleId: String)
}

/**
 * Creates, follows, downloads, plays and deletes one article's audio.
 *
 * @param repository null when the session has no audio, as in tests of other features.
 * @param newPlayer connects to the audio player, refreshing its state in the given scope; called
 *   once, when the audio is on this device or a sample plays.
 */
class ArticleAudioViewModel(
    private val articleId: String,
    private val repository: AudioRepository?,
    private val newPlayer: (CoroutineScope) -> ArticlePlayer,
    private val watcher: AudioJobWatcher,
    private val settings: AudioSettings,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<AudioUiState>(if (repository == null) AudioUiState.Unavailable else AudioUiState.Checking)
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<AudioMessage?>(null)
    val message: StateFlow<AudioMessage?> = _message.asStateFlow()

    private var article: Article? = null
    private var body: List<BodyBlock> = emptyList()
    private var parts: List<SpeechPart> = emptyList()
    private var voices: List<AudioVoice> = emptyList()
    private var checkJob: Job? = null
    private var pollJob: Job? = null
    private var sleepJob: Job? = null
    private var visible = false

    private val playerFlow = MutableStateFlow<ArticlePlayer?>(null)
    private val sleepAt = MutableStateFlow<Long?>(null)

    // flatMapLatest is still marked experimental.
    @OptIn(ExperimentalCoroutinesApi::class)
    val listenState: StateFlow<ListenState> =
        combine(
            _uiState,
            playerFlow.flatMapLatest { it?.state ?: flowOf(PlayerState()) },
            sleepAt,
        ) { state, player, sleep -> listenState(state, player, sleep) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ListenState())

    /**
     * Shows audio for [article] with [body], the reader's body (see [readerBody]). Called whenever
     * the reader loads the article; checks the server again when the article changed.
     */
    fun bind(
        article: Article,
        body: List<BodyBlock>,
        narration: List<NarrationPart> = emptyList(),
    ) {
        val previous = this.article
        this.article = article
        this.body = body
        parts = speechParts(article.title, article.summary, body, narration)
        if (repository == null) return
        if (article.shelf == Shelf.Trash) {
            // The server deletes audio when its article moves to Trash.
            if (previous != null && previous.shelf != Shelf.Trash) articleTrashed() else _uiState.value = AudioUiState.Unavailable
            return
        }
        if (previous == null || previous.revision != article.revision || previous.shelf == Shelf.Trash) check()
    }

    /** The reader is on screen ([visible] true) or not; a running job is only followed while it is. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible && _uiState.value is AudioUiState.Generating) poll()
        if (!visible) pollJob?.cancel()
    }

    private fun check() {
        val repository = repository ?: return
        checkJob?.cancel()
        checkJob =
            viewModelScope.launch {
                if (!_uiState.value.hasAudio) _uiState.value = AudioUiState.Checking
                try {
                    if (voices.isEmpty()) voices = repository.voices()
                    show(repository.audio(articleId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AudioUnavailableException) {
                    _uiState.value = AudioUiState.Unavailable
                } catch (e: IOException) {
                    _uiState.value = AudioUiState.CheckFailed
                }
            }
    }

    /** Shows [audio] as the server has it, downloading it when it is ready. */
    private suspend fun show(audio: ArticleAudio?) {
        when (audio?.status) {
            null -> {
                _uiState.value = AudioUiState.NotCreated(voices, speechCharacters(parts))
            }

            AudioStatus.Generating -> {
                _uiState.value = AudioUiState.Generating(audio)
                poll()
            }

            AudioStatus.Failed -> {
                _uiState.value = AudioUiState.Failed(audio)
            }

            AudioStatus.Ready -> {
                download(audio)
            }
        }
    }

    private suspend fun download(audio: ArticleAudio) {
        val repository = repository ?: return
        _uiState.value = AudioUiState.Downloading(audio, 0)
        try {
            val files =
                repository.download(articleId, audio) { present ->
                    _uiState.update { if (it is AudioUiState.Downloading) it.copy(filesPresent = present) else it }
                }
            if (playerFlow.value == null) playerFlow.value = newPlayer(viewModelScope)
            _uiState.value = AudioUiState.Ready(audio, files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _uiState.value = AudioUiState.CheckFailed
            _message.value = AudioMessage.DownloadFailed
        }
    }

    /** Checks a running job every few seconds while the reader is on screen. */
    private fun poll() {
        val repository = repository ?: return
        if (!visible || pollJob?.isActive == true) return
        pollJob =
            viewModelScope.launch {
                while (_uiState.value is AudioUiState.Generating) {
                    delay(POLL_INTERVAL_MS)
                    val audio =
                        try {
                            repository.audio(articleId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            continue
                        }
                    if (_uiState.value !is AudioUiState.Generating) break
                    if (audio?.status == AudioStatus.Generating) {
                        _uiState.value = AudioUiState.Generating(audio)
                    } else {
                        show(audio)
                        break
                    }
                }
            }
    }

    /** Asks the server to make the audio in [voiceId]. */
    fun create(voiceId: String) {
        val state = _uiState.value as? AudioUiState.NotCreated ?: return
        if (state.isCreating) return
        _uiState.value = state.copy(isCreating = true)
        startCreate(voiceId) { _uiState.value = state }
    }

    /** Tries a failed audio again, in the same voice. */
    fun retry() {
        val state = _uiState.value as? AudioUiState.Failed ?: return
        if (state.isRetrying) return
        _uiState.value = state.copy(isRetrying = true)
        startCreate(state.audio.voice.id) { _uiState.value = state }
    }

    private fun startCreate(
        voiceId: String,
        restore: () -> Unit,
    ) {
        val repository = repository ?: return
        val article = article ?: return
        viewModelScope.launch {
            try {
                val audio = repository.create(articleId, voiceId, article.revision, parts.map { it.segment })
                watcher.watch(articleId, article.title)
                show(audio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioConflictException) {
                restore()
                _message.value = AudioMessage.ChangedOnServer
                check()
            } catch (e: AudioRequestException) {
                restore()
                _message.value = AudioMessage.CreateFailed(e.message)
            } catch (e: IOException) {
                restore()
                _message.value = AudioMessage.CreateFailed(null)
            }
        }
    }

    /** Stops a running job; nothing is kept. */
    fun cancel() {
        val state = _uiState.value as? AudioUiState.Generating ?: return
        if (state.isCancelling) return
        _uiState.value = state.copy(isCancelling = true)
        deleteOnServer(onFailure = { _uiState.value = state })
    }

    /** Deletes the audio here and on the server, which unlocks the article's text. */
    fun delete() {
        val state = _uiState.value
        when (state) {
            is AudioUiState.Ready -> _uiState.value = state.copy(isDeleting = true)
            is AudioUiState.Failed, is AudioUiState.Downloading -> Unit
            else -> return
        }
        stopPlayback()
        deleteOnServer(onFailure = { _uiState.value = state }, deleted = AudioMessage.Deleted)
    }

    private fun deleteOnServer(
        onFailure: () -> Unit,
        deleted: AudioMessage? = null,
    ) {
        val repository = repository ?: return
        pollJob?.cancel()
        watcher.stop(articleId)
        viewModelScope.launch {
            try {
                repository.delete(articleId)
                _uiState.value = AudioUiState.NotCreated(voices, speechCharacters(parts))
                if (deleted != null) _message.value = deleted
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                onFailure()
                _message.value = AudioMessage.DeleteFailed
            }
        }
    }

    /**
     * The article moved to Trash, where the server deleted its audio: stops it and removes the
     * downloaded copy.
     */
    fun articleTrashed() {
        stopPlayback()
        pollJob?.cancel()
        watcher.stop(articleId)
        _uiState.value = AudioUiState.Unavailable
        val repository = repository ?: return
        viewModelScope.launch {
            try {
                repository.deleteDownloads(articleId)
            } catch (e: IOException) {
                // Left over files go when the session ends.
            }
        }
    }

    fun messageShown(message: AudioMessage) {
        _message.update { if (it == message) null else it }
    }

    /** Plays a short sample of [voiceId]. */
    fun playSample(voiceId: String) {
        val repository = repository ?: return
        viewModelScope.launch {
            try {
                val file = repository.sample(voiceId)
                val player = playerFlow.value ?: newPlayer(viewModelScope).also { playerFlow.value = it }
                player.playSample(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _message.value = AudioMessage.DownloadFailed
            }
        }
    }

    /** Plays the audio: resumes where it is, or starts at the beginning. */
    fun listen() {
        val ready = _uiState.value as? AudioUiState.Ready ?: return
        val player = playerFlow.value ?: return
        if (player.state.value.playlistId == playlistId(ready.audio)) {
            player.resume()
        } else {
            val title = article?.title.orEmpty()
            player.setSpeed(settings.speed)
            player.play(playlistId(ready.audio), ready.files, title, ready.audio.voice.name, playlistParts(ready.audio.segments))
        }
    }

    fun pause() {
        playerFlow.value?.pause()
    }

    /** Moves [deltaMs] back (negative) or forward through the whole audio, across segments. */
    fun skip(deltaMs: Long) {
        val listen = listenState.value
        seekToPosition(listen.positionMs + deltaMs)
    }

    /** Moves to [fraction] (0 to 1) of the whole audio. */
    fun seekToFraction(fraction: Float) {
        val ready = _uiState.value as? AudioUiState.Ready ?: return
        seekToPosition((ready.audio.segments.totalDurationMs() * fraction.coerceIn(0f, 1f)).toLong())
    }

    private fun seekToPosition(positionMs: Long) {
        val ready = _uiState.value as? AudioUiState.Ready ?: return
        val player = playerFlow.value ?: return
        if (player.state.value.playlistId != playlistId(ready.audio)) return
        var remaining = positionMs.coerceIn(0, ready.audio.segments.totalDurationMs())
        for (segment in ready.audio.segments) {
            if (remaining < segment.durationMs || segment == ready.audio.segments.last()) {
                player.seekTo(segment.index, remaining.coerceAtMost(segment.durationMs))
                return
            }
            remaining -= segment.durationMs
        }
    }

    /**
     * Goes to the start of the current section, or to the previous one when already at its start
     * (as a music player's previous button does).
     */
    fun previousSection() {
        val player = playerFlow.value ?: return
        val current = player.state.value
        val starts = sectionStarts()
        val start = starts.lastOrNull { it <= current.segmentIndex } ?: 0
        val target =
            if (start == current.segmentIndex &&
                current.positionMs < RESTART_THRESHOLD_MS
            ) {
                starts.lastOrNull { it < start } ?: 0
            } else {
                start
            }
        player.seekTo(target, 0)
    }

    /** Moves to the start of the previous (negative [delta]) or next part, about a paragraph. */
    fun stepPart(delta: Int) {
        val ready = _uiState.value as? AudioUiState.Ready ?: return
        val player = playerFlow.value ?: return
        val current = player.state.value
        // As with a music player, "previous" first goes back to the start of the current part.
        val target =
            if (delta < 0 && current.positionMs >= RESTART_THRESHOLD_MS) current.segmentIndex else current.segmentIndex + delta
        player.seekTo(target.coerceIn(0, ready.audio.segments.lastIndex), 0)
    }

    fun nextSection() {
        val player = playerFlow.value ?: return
        val target = sectionStarts().firstOrNull { it > player.state.value.segmentIndex } ?: return
        player.seekTo(target, 0)
    }

    fun setSpeed(speed: Float) {
        settings.speed = speed
        playerFlow.value?.setSpeed(speed)
    }

    /** Pauses playback after [minutes], or turns the timer off when null. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            sleepAt.value = null
            return
        }
        val delayMs = minutes * MILLIS_PER_MINUTE
        sleepAt.value = clock.millis() + delayMs
        sleepJob =
            viewModelScope.launch {
                delay(delayMs)
                playerFlow.value?.pause()
                sleepAt.value = null
            }
    }

    private fun stopPlayback() {
        val audio = (_uiState.value as? AudioUiState.Ready)?.audio ?: (_uiState.value as? AudioUiState.Downloading)?.audio
        if (audio != null) playerFlow.value?.stop(playlistId(audio))
        setSleepTimer(null)
    }

    /** The segments that start a section: those reading a heading. */
    private fun sectionStarts(): List<Int> =
        parts.indices.filter { index ->
            val block = parts[index].segment.block
            body.getOrNull(block) is BodyBlock.Heading && parts.getOrNull(index - 1)?.segment?.block != block
        }

    private fun playlistId(audio: ArticleAudio): String = playlistIdOf(articleId, audio.createdAt.toEpochMilli())

    /** Each segment's length and the section heading it reads in, for the home screen's player. */
    private fun playlistParts(segments: List<AudioSegment>): List<PlaylistPart> {
        val headings = body.withIndex().filter { it.value is BodyBlock.Heading }
        return segments.map { segment ->
            PlaylistPart(segment.durationMs, headings.lastOrNull { it.index <= segment.block }?.value?.headingText())
        }
    }

    private fun listenState(
        state: AudioUiState,
        player: PlayerState,
        sleep: Long?,
    ): ListenState {
        val ready = state as? AudioUiState.Ready ?: return ListenState()
        val segments = ready.audio.segments
        val duration = segments.totalDurationMs()
        if (player.playlistId != playlistId(ready.audio)) {
            return ListenState(durationMs = duration, speed = settings.speed, sleepAtMillis = sleep)
        }
        val index = player.segmentIndex.coerceIn(0, segments.lastIndex)
        val segment = segments[index]
        val position = segments.take(index).sumOf { it.durationMs } + player.positionMs.coerceAtMost(segment.durationMs)
        val currentBlock = segment.block
        val headings = body.withIndex().filter { it.value is BodyBlock.Heading }
        return ListenState(
            isLoaded = true,
            isPlaying = player.isPlaying,
            positionMs = position,
            durationMs = duration,
            speed = player.speed,
            section = headings.lastOrNull { it.index <= currentBlock }?.value?.headingText(),
            highlight = highlight(index, segment, player.positionMs),
            sleepAtMillis = sleep,
            upNext = headings.filter { it.index > currentBlock }.take(UP_NEXT_COUNT).mapNotNull { it.value.headingText() },
        )
    }

    /** The block and sentence [segment] is reading [positionMs] into it. */
    private fun highlight(
        index: Int,
        segment: AudioSegment,
        positionMs: Long,
    ): SpokenHighlight? {
        if (segment.block == HEADER_BLOCK) return null
        val part = parts.getOrNull(index)
        val start = part?.blockTextStart
        // The server reads what the reader sent; if this copy of the body differs, only the block shows.
        if (part == null || start == null || !part.segment.reads(segment)) return SpokenHighlight(segment.block, null)
        val codePoint = segment.charStartsMs.indexOfLast { it <= positionMs }.coerceAtLeast(0)
        val text = segment.text
        val offset = text.offsetByCodePoints(0, codePoint.coerceAtMost(text.codePointCount(0, text.length)))
        val sentence = sentenceAt(text, offset) ?: return SpokenHighlight(segment.block, null)
        return SpokenHighlight(segment.block, (sentence.first + start)..(sentence.last + start))
    }

    override fun onCleared() {
        playerFlow.value?.release()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val UP_NEXT_COUNT = 3
    }
}

private fun List<AudioSegment>.totalDurationMs(): Long = sumOf { it.durationMs }

/** Whether this part shows the same text, in the same block, as the server's [segment]. */
private fun SpeechSegment.reads(segment: AudioSegment) = block == segment.block && text == segment.text

private fun BodyBlock.headingText(): String? =
    (this as? BodyBlock.Heading)
        ?.text
        ?.text
        ?.replace('\n', ' ')
        ?.trim()
