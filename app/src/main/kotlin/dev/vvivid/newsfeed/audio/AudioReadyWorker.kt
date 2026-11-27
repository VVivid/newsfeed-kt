package dev.vvivid.newsfeed.audio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.vvivid.newsfeed.MainActivity
import dev.vvivid.newsfeed.NewsfeedApplication
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.auth.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Follows an article's audio job on the server after the reader has closed: when the audio is
 * ready it downloads it, so it plays offline, and posts a notification. It gives up after about ten
 * minutes, the most WorkManager lets one run take; the reader picks up from there when opened.
 */
class AudioReadyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val articleId = inputData.getString(KEY_ARTICLE_ID) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val container = (applicationContext as NewsfeedApplication).container
        val state =
            withTimeoutOrNull(SESSION_WAIT_MS) {
                container.sessionManager.state.first { it !is SessionState.Restoring }
            }
        val repository = (state as? SessionState.SignedIn)?.session?.audio ?: return Result.success()
        repeat(MAX_CHECKS) {
            val audio =
                try {
                    repository.audio(articleId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // Offline for a moment: check again later.
                    delay(CHECK_INTERVAL_MS)
                    return@repeat
                }
            when (audio?.status) {
                // Deleted meanwhile, or failed: the reader shows why when it opens.
                null, AudioStatus.Failed -> {
                    return Result.success()
                }

                AudioStatus.Ready -> {
                    try {
                        repository.download(articleId, audio)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        // The reader downloads it when opened.
                    }
                    notifyReady(applicationContext, title)
                    return Result.success()
                }

                AudioStatus.Generating -> {
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val KEY_ARTICLE_ID = "article_id"
        private const val KEY_TITLE = "title"
        private const val CHANNEL_ID = "audio_ready"
        private const val SESSION_WAIT_MS = 30_000L
        private const val CHECK_INTERVAL_MS = 10_000L

        /** About nine minutes of checks, inside WorkManager's ten-minute limit for one run. */
        private const val MAX_CHECKS = 54

        /** Starts following [articleId]'s audio job, replacing an earlier follow of the same article. */
        fun start(
            context: Context,
            articleId: String,
            title: String,
        ) {
            val request =
                OneTimeWorkRequestBuilder<AudioReadyWorker>()
                    .setInputData(workDataOf(KEY_ARTICLE_ID to articleId, KEY_TITLE to title))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(articleId), ExistingWorkPolicy.REPLACE, request)
        }

        /** Stops following [articleId]'s audio job, as when the user cancels or deletes it. */
        fun stop(
            context: Context,
            articleId: String,
        ) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(articleId))
        }

        private fun workName(articleId: String) = "audio-ready-$articleId"

        private fun notifyReady(
            context: Context,
            title: String,
        ) {
            val allowed =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!allowed) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.audio_ready_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val openApp =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_headphones)
                    .setContentTitle(context.getString(R.string.audio_ready_title))
                    .setContentText(title)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
            } catch (e: SecurityException) {
                // The permission was taken back after the check.
            }
        }
    }
}
