package dev.vvivid.newsfeed.audio

import android.content.Context
import androidx.core.content.edit
import dev.vvivid.newsfeed.reader.AudioJobWatcher
import dev.vvivid.newsfeed.reader.AudioSettings

/** Keeps the listening speed in the app's preferences, so it stays across articles and launches. */
class PreferencesAudioSettings(
    context: Context,
) : AudioSettings {
    private val preferences = context.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE)

    override var speed: Float
        get() = preferences.getFloat(KEY_SPEED, 1f)
        set(value) {
            preferences.edit { putFloat(KEY_SPEED, value) }
        }

    private companion object {
        const val KEY_SPEED = "speed"
    }
}

/** Follows audio jobs with [AudioReadyWorker]. */
class WorkManagerAudioJobWatcher(
    context: Context,
) : AudioJobWatcher {
    private val appContext = context.applicationContext

    override fun watch(
        articleId: String,
        title: String,
    ) = AudioReadyWorker.start(appContext, articleId, title)

    override fun stop(articleId: String) = AudioReadyWorker.stop(appContext, articleId)
}
