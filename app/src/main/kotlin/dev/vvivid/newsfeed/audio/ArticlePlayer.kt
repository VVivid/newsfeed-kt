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
 * @property title the article's title, and [voiceName] the voice reading it.
 * @property elapsedMs how far into the whole audio the voice is, and [totalMs] how long it is; both
 *   0 when the playlist was loaded without its parts' lengths.
 * @property section the heading of the section being read, if any.
 */
data class PlayerState(
    val playlistId: String? = null,
    val isPlaying: Boolean = false,
    val segmentIndex: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val title: String? = null,
    val voiceName: String? = null,
    val elapsedMs: Long = 0,
    val totalMs: Long = 0,
    val section: String? = null,
) {
    /** The article the loaded audio belongs to, see [playlistIdOf]. */
    val articleId: String? get() = playlistId?.substringBefore(PLAYLIST_ID_SEPARATOR)
}

/** One file of a playlist as screens other than the reader show it. */
data class PlaylistPart(
    val durationMs: Long,
    /** The heading of the section the part reads in; null above the first heading. */
    val section: String?,
)

private const val PLAYLIST_ID_SEPARATOR = '/'

/** The playlist ID of [articleId]'s audio created at [createdAtMillis]. */
fun playlistIdOf(
    articleId: String,
    createdAtMillis: Long,
): String = "$articleId$PLAYLIST_ID_SEPARATOR$createdAtMillis"

/** Plays an article's audio files in order, with the controls the reader offers. */
interface ArticlePlayer {
    /** The current state. [positionMs] is refreshed about every 100 ms while playing. */
    val state: StateFlow<PlayerState>

    /**
     * Loads [files] as [playlistId] and plays from [segmentIndex] at [positionMs]. [title] and
     * [voiceName] are shown on the lock screen and in the notification. [parts], one per file, let
     * other screens show progress and the section without the article (see [PlayerState]).
     */
    fun play(
        playlistId: String,
        files: List<File>,
        title: String,
        voiceName: String,
        parts: List<PlaylistPart> = emptyList(),
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

    /** Moves [deltaMs] back (negative) or forward through the whole loaded audio, across files. */
    fun skip(deltaMs: Long)

    /** Stops and unloads the playlist, if [playlistId] is the one loaded. */
    fun stop(playlistId: String)

    /** Plays a voice sample once, apart from the article audio. */
    fun playSample(file: File)

    /** Lets go of the player connection. Audio that is playing goes on in the background. */
    fun release()
}
