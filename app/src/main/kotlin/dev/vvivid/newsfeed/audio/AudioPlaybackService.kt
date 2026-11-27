package dev.vvivid.newsfeed.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.vvivid.newsfeed.MainActivity
import java.io.File

/**
 * Plays article audio in the background, so it goes on with the screen off or in another app, with
 * controls on the lock screen and in the notification. The reader controls it through a
 * MediaController (see [MediaArticlePlayer]).
 */
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player =
            ExoPlayer
                .Builder(this)
                // Speech: pauses for calls and other apps' audio, and when headphones come out.
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                // The files are local, so only the CPU needs to stay awake with the screen off.
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
                .build()
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session =
            MediaSession
                .Builder(this, player)
                .setSessionActivity(openApp)
                .setCallback(SessionCallback(File(filesDir, AUDIO_DIR).canonicalFile))
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops paused audio; audio that is playing goes on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * The session is open to other apps, such as the system's media controls and Bluetooth devices,
     * which may play, pause and seek. Only this app may load audio, and only its own downloaded
     * files: another app must not make it play files from its private storage. Setting and adding
     * items both come through here.
     */
    private inner class SessionCallback(
        private val audioDir: File,
    ) : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (controller.packageName != packageName) return Futures.immediateFailedFuture(UnsupportedOperationException())
            val items =
                mediaItems.map { item ->
                    val uri = item.requestMetadata.mediaUri ?: return Futures.immediateFailedFuture(IllegalArgumentException())
                    val path = uri.path?.let { File(it).canonicalFile }
                    if (uri.scheme != "file" || path == null || !path.startsWith(audioDir)) {
                        return Futures.immediateFailedFuture(IllegalArgumentException())
                    }
                    item.buildUpon().setUri(uri).build()
                }
            return Futures.immediateFuture(items.toMutableList())
        }
    }

    companion object {
        /** The skip back and forward buttons move 15 seconds. */
        const val SEEK_INCREMENT_MS = 15_000L

        /** Downloaded article audio, under the app's files directory (see ServerAudioRepository). */
        const val AUDIO_DIR = "audio"
    }
}
