package dev.vvivid.newsfeed.audio

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What is loaded in the audio player, which keeps playing after the reader closes.
 *
 * @property playlistId names the audio being played, see [playlistIdOf]; null when nothing is
 *   loaded.
 * @property segmentIndex the file being played, an index into the playlist.
 * @property positionMs the position in that file.
 */
data class PlayerState(
    val playlistId: String? = null,
    val isPlaying: Boolean = false,
    val segmentIndex: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
)

/** The playlist ID of [articleId]'s audio created at [createdAtMillis]. */
fun playlistIdOf(
    articleId: String,
    createdAtMillis: Long,
): String = "$articleId/$createdAtMillis"

/** Plays an article's audio files in order, with the controls the reader offers. */
interface ArticlePlayer {
    /** The current state. [positionMs] is refreshed about every 100 ms while playing. */
    val state: StateFlow<PlayerState>

    /**
     * Loads [files] as [playlistId] and plays from [segmentIndex] at [positionMs]. [title] and
     * [voiceName] are shown on the lock screen and in the notification.
     */
    fun play(
        playlistId: String,
        files: List<File>,
        title: String,
        voiceName: String,
        segmentIndex: Int = 0,
        positionMs: Long = 0,
    )

    fun resume()

    fun pause()

    /** Moves to [positionMs] in file [segmentIndex] of the loaded playlist. */
    fun seekTo(
        segmentIndex: Int,
        positionMs: Long,
    )

    fun setSpeed(speed: Float)

    /** Stops and unloads the playlist, if [playlistId] is the one loaded. */
    fun stop(playlistId: String)

    /** Plays a voice sample once, apart from the article audio. */
    fun playSample(file: File)

    /** Lets go of the player connection. Audio that is playing goes on in the background. */
    fun release()
}
