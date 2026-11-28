package dev.vvivid.newsfeed.audio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.Instant

/** `GET /api/v1/audio/voices`. */
@Serializable
internal class VoiceListJson(
    val voices: List<VoiceJson>? = null,
)

@Serializable
internal class VoiceJson(
    val id: String,
    val name: String,
    val description: String = "",
)

/** `GET /api/v1/articles/{id}/audio`, and the answer to creating audio. */
@Serializable
internal class ArticleAudioJson(
    val status: String,
    val voice: VoiceNameJson,
    @SerialName("created_at") val createdAt: Long,
    val characters: Int = 0,
    @SerialName("segments_total") val segmentsTotal: Int = 0,
    @SerialName("segments_done") val segmentsDone: Int = 0,
    val error: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val segments: List<AudioSegmentJson>? = null,
) {
    fun toAudio(): ArticleAudio {
        val status =
            when (status) {
                "generating" -> AudioStatus.Generating
                "ready" -> AudioStatus.Ready
                "failed" -> AudioStatus.Failed
                else -> throw IOException("Unknown audio status")
            }
        val segments = segments.orEmpty().map { it.toSegment() }
        // A ready audio is only usable with every segment in order.
        if (status == AudioStatus.Ready && (segments.isEmpty() || segments.withIndex().any { (i, s) -> s.index != i })) {
            throw IOException("The ready audio's segments are incomplete")
        }
        return ArticleAudio(
            status = status,
            voice = AudioVoiceName(voice.id, voice.name),
            createdAt = Instant.ofEpochMilli(createdAt),
            characters = characters,
            segmentsTotal = segmentsTotal,
            segmentsDone = segmentsDone,
            error = error?.ifBlank { null },
            durationMs = durationMs,
            segments = segments,
        )
    }
}

@Serializable
internal class VoiceNameJson(
    val id: String,
    val name: String,
)

@Serializable
internal class AudioSegmentJson(
    val index: Int,
    val block: Int,
    val text: String,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("char_starts_ms") val charStartsMs: List<Int>? = null,
) {
    fun toSegment(): AudioSegment = AudioSegment(index, block, text, durationMs, charStartsMs.orEmpty())
}

/** The body of `POST /api/v1/articles/{id}/audio`. */
@Serializable
internal class CreateAudioJson(
    val voice: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    val segments: List<SpeechSegmentJson>,
)

@Serializable
internal class SpeechSegmentJson(
    val block: Int,
    val text: String,
    val spoken: String? = null,
    /** Pairs of code point offsets, shown then spoken; left out when empty. */
    val sync: List<List<Int>>? = null,
)

/** The server's error body. */
@Serializable
internal class ErrorJson(
    val error: String? = null,
)
