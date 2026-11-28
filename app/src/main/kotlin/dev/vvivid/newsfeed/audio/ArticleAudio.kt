package dev.vvivid.newsfeed.audio

import java.io.File
import java.io.IOException
import java.time.Instant

/** A voice the server can read articles in, such as "Talia". */
data class AudioVoice(
    val id: String,
    val name: String,
    val description: String,
)

/** Where an article's audio is on the server. */
enum class AudioStatus {
    /** The server is still making it, one segment after another. */
    Generating,

    /** Every segment is made; the audio can be downloaded and played. */
    Ready,

    /** The server gave up; [ArticleAudio.error] says why. Creating it again replaces it. */
    Failed,
}

/**
 * An article's audio, as the server describes it. While it exists, the server keeps the article's
 * text locked, so the segments always match the text the reader shows.
 *
 * @property createdAt identifies this audio: new audio for the same article gets a new time, so it
 *   also names the folder its downloaded files are kept in.
 * @property characters how many characters the server sends to the voice service, which is what it
 *   costs.
 * @property segments empty until the audio is [AudioStatus.Ready].
 */
data class ArticleAudio(
    val status: AudioStatus,
    val voice: AudioVoiceName,
    val createdAt: Instant,
    val characters: Int,
    val segmentsTotal: Int,
    val segmentsDone: Int,
    val error: String?,
    val durationMs: Long,
    val segments: List<AudioSegment>,
)

/** The voice an audio was made with. */
data class AudioVoiceName(
    val id: String,
    val name: String,
)

/**
 * One spoken part of an article: one MP3 file.
 *
 * @property block the index of the reader body block this part reads, or [HEADER_BLOCK] for the
 *   title and summary.
 * @property charStartsMs when each character of [text] starts, in milliseconds, one entry per
 *   Unicode code point. Used to follow the current sentence.
 */
data class AudioSegment(
    val index: Int,
    val block: Int,
    val text: String,
    val durationMs: Long,
    val charStartsMs: List<Int>,
)

/**
 * A part of the article to be read aloud, as the app sends it when creating audio. [text] is what
 * the reader shows. With the article's narration script, the voice says [spoken] instead, and
 * [sync] ties the two together: each point pairs a code point offset in [text] with the offset in
 * [spoken] where the same words start.
 */
data class SpeechSegment(
    val block: Int,
    val text: String,
    val spoken: String? = null,
    val sync: List<SyncPoint> = emptyList(),
)

/** Code point offsets in a segment's shown text ([shown]) and spoken text ([spoken]). */
data class SyncPoint(
    val shown: Int,
    val spoken: Int,
)

/** The block index of the segment that reads the title and summary, above the body. */
const val HEADER_BLOCK = -1

/**
 * The server cannot make audio: it has no voice service set up, or it is a version without audio.
 * The reader then offers no audio at all.
 */
class AudioUnavailableException : IOException("The server does not make audio")

/**
 * HTTP 409 on creating audio: the article changed since the reader loaded it, or it has audio
 * already. The reader reloads both.
 */
class AudioConflictException(
    message: String,
) : IOException(message)

/**
 * The server refused a request about audio with a message meant for people, such as why audio
 * could not be created.
 */
class AudioRequestException(
    message: String,
) : IOException(message)

/**
 * Article audio on the user's server, and the downloaded copies that let it play offline. Methods
 * are main-safe and throw [IOException]s when a request fails.
 */
interface AudioRepository {
    /** The voices to choose from. Throws [AudioUnavailableException] when the server has none. */
    suspend fun voices(): List<AudioVoice>

    /** A short sample of [voiceId], as a local MP3 file. */
    suspend fun sample(voiceId: String): File

    /** The article's audio, or null when it has none. */
    suspend fun audio(articleId: String): ArticleAudio?

    /**
     * Asks the server to make audio of [segments] in [voiceId]. [expectedRevision] is the revision
     * the segments were made from, so the server refuses with [AudioConflictException] if the text
     * changed meanwhile.
     */
    suspend fun create(
        articleId: String,
        voiceId: String,
        expectedRevision: Long,
        segments: List<SpeechSegment>,
    ): ArticleAudio

    /** Deletes the article's audio on the server and on this device. Also stops a running job. */
    suspend fun delete(articleId: String)

    /**
     * The local files of a ready [audio], one per segment in order, downloading the ones this device
     * does not have yet. [onProgress] gets the number of files present after each one. Files of the
     * article's older audio are deleted.
     */
    suspend fun download(
        articleId: String,
        audio: ArticleAudio,
        onProgress: (filesPresent: Int) -> Unit = {},
    ): List<File>

    /** Deletes the article's downloaded audio on this device only, as when it moves to Trash. */
    suspend fun deleteDownloads(articleId: String)
}

/**
 * Where downloaded article audio and voice samples are kept: [storage] for the audio files, which
 * must survive until the audio is deleted, and [cache] for samples Android may clear.
 */
class AudioDirs(
    val storage: File,
    val cache: File,
) {
    fun deleteAll() {
        storage.deleteRecursively()
        File(cache, SAMPLES_DIR).deleteRecursively()
    }

    companion object {
        const val SAMPLES_DIR = "audio-samples"
    }
}
