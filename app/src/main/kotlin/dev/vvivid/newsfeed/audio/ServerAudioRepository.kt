package dev.vvivid.newsfeed.audio

import dev.vvivid.newsfeed.auth.SessionEndedException
import dev.vvivid.newsfeed.data.ArticleNotFoundException
import dev.vvivid.newsfeed.data.PermissionDeniedException
import dev.vvivid.newsfeed.data.UnexpectedResponseException
import dev.vvivid.newsfeed.net.ServerJson
import dev.vvivid.newsfeed.net.bodyBytes
import dev.vvivid.newsfeed.net.bodyText
import dev.vvivid.newsfeed.net.send
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

/**
 * The [AudioRepository] of a signed-in server: its audio API under `/api/v1/audio` and
 * `/api/v1/articles/{id}/audio`. [httpClient] carries the session's credentials.
 *
 * Downloaded audio lives in [storageDir], one folder per article and audio
 * (`<article ID>/<created at>/<segment>.mp3`), so a replaced audio never mixes with the old one.
 * Samples live in [cacheDir], which Android may clear.
 */
class ServerAudioRepository(
    private val httpClient: OkHttpClient,
    serverOrigin: String,
    private val storageDir: File,
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioRepository {
    private val apiUrl: HttpUrl =
        serverOrigin
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("api/v1")
            .build()

    override suspend fun voices(): List<AudioVoice> {
        val url = apiUrl.newBuilder().addPathSegments("audio/voices").build()
        val list =
            try {
                requestJson(Request.Builder().url(url).get(), VoiceListJson.serializer())
            } catch (e: ArticleNotFoundException) {
                // A server from before audio existed has no such route.
                throw AudioUnavailableException()
            }
        return list.voices
            .orEmpty()
            .map { AudioVoice(it.id, it.name, it.description) }
            .ifEmpty { throw AudioUnavailableException() }
    }

    override suspend fun sample(voiceId: String): File {
        val url =
            apiUrl
                .newBuilder()
                .addPathSegments("audio/voices")
                .addPathSegment(voiceId)
                .addPathSegment("sample")
                .build()
        val file = File(File(cacheDir, AudioDirs.SAMPLES_DIR), "${safeName(voiceId)}.mp3")
        if (file.isFile) return file
        val bytes = requestBytes(Request.Builder().url(url).get(), MAX_SAMPLE_BYTES)
        withContext(ioDispatcher) { writeAtomically(file, bytes) }
        return file
    }

    override suspend fun audio(articleId: String): ArticleAudio? {
        val url = audioUrl(articleId).build()
        return try {
            requestJson(Request.Builder().url(url).get(), ArticleAudioJson.serializer()).toAudio()
        } catch (e: ArticleNotFoundException) {
            null
        }
    }

    override suspend fun create(
        articleId: String,
        voiceId: String,
        expectedRevision: Long,
        segments: List<SpeechSegment>,
    ): ArticleAudio {
        val url = audioUrl(articleId).build()
        val body =
            ServerJson.encodeToString(
                CreateAudioJson.serializer(),
                CreateAudioJson(
                    voiceId,
                    expectedRevision,
                    segments.map { segment ->
                        SpeechSegmentJson(
                            segment.block,
                            segment.text,
                            segment.spoken,
                            segment.sync.map { listOf(it.shown, it.spoken) }.ifEmpty { null },
                        )
                    },
                ),
            )
        return requestJson(Request.Builder().url(url).post(body.toRequestBody(JsonMediaType)), ArticleAudioJson.serializer()).toAudio()
    }

    override suspend fun delete(articleId: String) {
        val url = audioUrl(articleId).build()
        val request =
            Request
                .Builder()
                .url(url)
                .delete()
                .build()
        httpClient.send(request, ioDispatcher) { response ->
            // 404: there was no audio, which is what deleting asks for.
            if (response.code != 204 && response.code != 200 && response.code != 404) response.throwIfError()
        }
        deleteDownloads(articleId)
    }

    override suspend fun download(
        articleId: String,
        audio: ArticleAudio,
        onProgress: (filesPresent: Int) -> Unit,
    ): List<File> {
        val articleDir = articleDir(articleId)
        val audioDir = File(articleDir, audio.createdAt.toEpochMilli().toString())
        withContext(ioDispatcher) {
            // Older audio of the same article is not needed any more.
            articleDir.listFiles()?.filter { it != audioDir }?.forEach { it.deleteRecursively() }
        }
        val files = audio.segments.map { File(audioDir, "${it.index}.mp3") }
        var present = withContext(ioDispatcher) { files.count { it.isFile } }
        onProgress(present)
        for ((segment, file) in audio.segments.zip(files)) {
            if (withContext(ioDispatcher) { file.isFile }) continue
            val url =
                audioUrl(articleId)
                    .addPathSegment("segments")
                    .addPathSegment(segment.index.toString())
                    .build()
            val bytes = requestBytes(Request.Builder().url(url).get(), MAX_SEGMENT_BYTES)
            withContext(ioDispatcher) { writeAtomically(file, bytes) }
            present++
            onProgress(present)
        }
        return files
    }

    override suspend fun deleteDownloads(articleId: String) {
        withContext(ioDispatcher) { articleDir(articleId).deleteRecursively() }
    }

    private fun audioUrl(articleId: String): HttpUrl.Builder =
        apiUrl
            .newBuilder()
            .addPathSegment("articles")
            .addPathSegment(articleId)
            .addPathSegment("audio")

    private fun articleDir(articleId: String): File = File(storageDir, safeName(articleId))

    private suspend fun <T> requestJson(
        builder: Request.Builder,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val request = builder.header("Accept", "application/json").build()
        return httpClient.send(request, ioDispatcher) { response ->
            response.throwIfError()
            val json = response.bodyText(MAX_JSON_BYTES)
            try {
                ServerJson.decodeFromString(deserializer, json)
            } catch (e: IllegalArgumentException) {
                throw UnexpectedResponseException("The server's audio JSON is not valid", e)
            }
        }
    }

    private suspend fun requestBytes(
        builder: Request.Builder,
        maxBytes: Long,
    ): ByteArray =
        httpClient.send(builder.build(), ioDispatcher) { response ->
            response.throwIfError()
            response.bodyBytes(maxBytes)
        }

    private fun Response.throwIfError() {
        when (code) {
            200, 202 -> Unit
            401 -> throw SessionEndedException()
            403 -> throw PermissionDeniedException()
            404 -> throw ArticleNotFoundException()
            409 -> throw AudioConflictException(errorMessage() ?: "The article changed on the server")
            503 -> throw AudioUnavailableException()
            in 400..499 -> throw AudioRequestException(errorMessage() ?: "The server refused the audio request")
            else -> throw UnexpectedResponseException("The server answered HTTP $code")
        }
    }

    /** The server's `{"error": …}` message, which is written to be shown. */
    private fun Response.errorMessage(): String? =
        try {
            ServerJson.decodeFromString(ErrorJson.serializer(), bodyText(MAX_ERROR_BYTES)).error?.ifBlank { null }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    private companion object {
        /** A ready audio's JSON carries each character's start time; a long article stays well below this. */
        const val MAX_JSON_BYTES = 16L * 1024 * 1024
        const val MAX_ERROR_BYTES = 16L * 1024

        /** A segment is at most 4500 bytes of text, a few minutes of speech. */
        const val MAX_SEGMENT_BYTES = 32L * 1024 * 1024
        const val MAX_SAMPLE_BYTES = 4L * 1024 * 1024

        val JsonMediaType = "application/json".toMediaType()
    }
}

/**
 * [value] as a file name. Server IDs are letters and digits; anything else is refused instead of
 * being allowed to name a path outside the audio folder.
 */
private fun safeName(value: String): String {
    if (!value.matches(SafeNamePattern)) throw IOException("Unexpected ID")
    return value
}

private val SafeNamePattern = Regex("[A-Za-z0-9_-]{1,128}")

/** Writes [bytes] to a temporary file and renames it, so a cut-off download never looks complete. */
private fun writeAtomically(
    file: File,
    bytes: ByteArray,
) {
    file.parentFile?.mkdirs()
    val partial = File(file.parentFile, "${file.name}.part")
    partial.writeBytes(bytes)
    if (!partial.renameTo(file)) {
        partial.delete()
        throw IOException("Could not save the audio file")
    }
}
