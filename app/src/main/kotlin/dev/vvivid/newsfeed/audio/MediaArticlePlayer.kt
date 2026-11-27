package dev.vvivid.newsfeed.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * The [ArticlePlayer] of the app: a MediaController of [AudioPlaybackService], so audio goes on
 * when the reader closes. It connects when created; commands given before that wait for the
 * connection. Call it on the main thread only.
 *
 * @param scope refreshes [state] while it is active, normally the reader's view model scope.
 */
class MediaArticlePlayer(
    context: Context,
    scope: CoroutineScope,
) : ArticlePlayer {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private var released = false
    private var samplePlayer: ExoPlayer? = null

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                refresh()
            }
        }

    private val connection =
        MediaController
            .Builder(appContext, SessionToken(appContext, ComponentName(appContext, AudioPlaybackService::class.java)))
            .buildAsync()

    private val ticker: Job

    init {
        connection.addListener(
            {
                val connected =
                    try {
                        connection.get()
                    } catch (e: ExecutionException) {
                        null
                    }
                if (connected == null || released) {
                    connected?.release()
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                waiting.forEach { it(connected) }
                waiting.clear()
                refresh()
            },
            ContextCompat.getMainExecutor(appContext),
        )
        ticker =
            scope.launch {
                while (isActive) {
                    refresh()
                    delay(if (_state.value.isPlaying) PLAYING_REFRESH_MS else PAUSED_REFRESH_MS)
                }
            }
    }

    private fun withController(command: (MediaController) -> Unit) {
        val connected = controller
        if (connected != null) command(connected) else waiting += command
    }

    private fun refresh() {
        val player = controller ?: return
        val mediaId = player.currentMediaItem?.mediaId
        _state.value =
            PlayerState(
                playlistId = mediaId?.substringBeforeLast(INDEX_SEPARATOR, missingDelimiterValue = "")?.ifEmpty { null },
                isPlaying = player.isPlaying,
                segmentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                speed = player.playbackParameters.speed,
            )
    }

    override fun play(
        playlistId: String,
        files: List<File>,
        title: String,
        voiceName: String,
        segmentIndex: Int,
        positionMs: Long,
    ) {
        val items =
            files.mapIndexed { index, file ->
                val uri = Uri.fromFile(file)
                MediaItem
                    .Builder()
                    .setMediaId("$playlistId$INDEX_SEPARATOR$index")
                    .setUri(uri)
                    // The session reads the address from here (see AudioPlaybackService).
                    .setRequestMetadata(
                        MediaItem.RequestMetadata
                            .Builder()
                            .setMediaUri(uri)
                            .build(),
                    ).setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(title)
                            .setArtist(voiceName)
                            .build(),
                    ).build()
            }
        withController { player ->
            player.setMediaItems(items, segmentIndex, positionMs)
            player.prepare()
            player.play()
        }
    }

    override fun resume() =
        withController { player ->
            // After the last part ends, playing again starts from the beginning.
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
            player.play()
        }

    override fun pause() = withController { it.pause() }

    override fun seekTo(
        segmentIndex: Int,
        positionMs: Long,
    ) = withController { it.seekTo(segmentIndex, positionMs) }

    override fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    override fun stop(playlistId: String) =
        withController { player ->
            if (player.currentMediaItem?.mediaId?.startsWith("$playlistId$INDEX_SEPARATOR") == true) {
                player.stop()
                player.clearMediaItems()
            }
        }

    override fun playSample(file: File) {
        val player = samplePlayer ?: ExoPlayer.Builder(appContext).build().also { samplePlayer = it }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()
    }

    override fun release() {
        released = true
        ticker.cancel()
        samplePlayer?.release()
        samplePlayer = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        waiting.clear()
        MediaController.releaseFuture(connection)
    }

    private companion object {
        /** Media IDs are the playlist ID, this separator and the segment index. */
        const val INDEX_SEPARATOR = '#'
        const val PLAYING_REFRESH_MS = 100L
        const val PAUSED_REFRESH_MS = 500L
    }
}
