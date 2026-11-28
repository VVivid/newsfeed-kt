package dev.vvivid.newsfeed.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** An [ArticlePlayer] that only remembers what it was told. */
internal class FakeArticlePlayer : ArticlePlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state
    val stopped = mutableListOf<String>()
    val skips = mutableListOf<Long>()
    var lastSeek: Pair<Int, Long>? = null
    var lastParts: List<PlaylistPart> = emptyList()
    var released = false

    fun move(
        segmentIndex: Int,
        positionMs: Long,
    ) {
        _state.value = _state.value.copy(segmentIndex = segmentIndex, positionMs = positionMs)
    }

    /** Shows [state] as the player's, as if another screen had started it. */
    fun show(state: PlayerState) {
        _state.value = state
    }

    override fun play(
        playlistId: String,
        files: List<File>,
        title: String,
        voiceName: String,
        parts: List<PlaylistPart>,
        segmentIndex: Int,
        positionMs: Long,
    ) {
        lastParts = parts
        _state.value =
            _state.value.copy(
                playlistId = playlistId,
                isPlaying = true,
                segmentIndex = segmentIndex,
                positionMs = positionMs,
                title = title,
                voiceName = voiceName,
            )
    }

    override fun resume() {
        _state.value = _state.value.copy(isPlaying = true)
    }

    override fun pause() {
        _state.value = _state.value.copy(isPlaying = false)
    }

    override fun seekTo(
        segmentIndex: Int,
        positionMs: Long,
    ) {
        lastSeek = segmentIndex to positionMs
        move(segmentIndex, positionMs)
    }

    override fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
    }

    override fun skip(deltaMs: Long) {
        skips += deltaMs
    }

    override fun stop(playlistId: String) {
        stopped += playlistId
        _state.value = PlayerState()
    }

    override fun playSample(file: File) = Unit

    override fun release() {
        released = true
    }
}
