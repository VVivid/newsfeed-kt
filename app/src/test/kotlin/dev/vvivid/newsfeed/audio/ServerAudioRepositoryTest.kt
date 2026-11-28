package dev.vvivid.newsfeed.audio

import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
import dev.vvivid.newsfeed.net.nextRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant

class ServerAudioRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun repository() =
        ServerAudioRepository(
            httpClient = newHttpClient(),
            serverOrigin = server.url("/").toString(),
            storageDir = File(folder.root, "audio"),
            cacheDir = File(folder.root, "cache"),
            ioDispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun `voices come from the voices route`() =
        runTest {
            server.enqueue(jsonResponse("""{"voices":[{"id":"talia","name":"Talia","description":"Calm"}]}"""))

            val voices = repository().voices()

            assertEquals(listOf(AudioVoice("talia", "Talia", "Calm")), voices)
            assertEquals("/api/v1/audio/voices", server.nextRequest().url.encodedPath)
        }

    @Test
    fun `a server without audio, or without voices, makes no audio`() =
        runTest {
            server.enqueue(jsonResponse("""{"error":"not found"}""", code = 404))
            server.enqueue(jsonResponse("""{"error":"Audio isn't set up"}""", code = 503))
            server.enqueue(jsonResponse("""{"voices":[]}"""))
            val repository = repository()

            repeat(3) { assertThrows<AudioUnavailableException> { repository.voices() } }
        }

    @Test
    fun `an article without audio has none`() =
        runTest {
            server.enqueue(jsonResponse("""{"error":"no audio"}""", code = 404))

            assertNull(repository().audio("A1"))
            assertEquals("/api/v1/articles/A1/audio", server.nextRequest().url.encodedPath)
        }

    @Test
    fun `ready audio carries its segments and timings`() =
        runTest {
            server.enqueue(jsonResponse(readyJson))

            val audio = repository().audio("A1")

            assertEquals(
                ArticleAudio(
                    status = AudioStatus.Ready,
                    voice = AudioVoiceName("talia", "Talia"),
                    createdAt = Instant.ofEpochMilli(1_000),
                    characters = 30,
                    segmentsTotal = 2,
                    segmentsDone = 2,
                    error = null,
                    durationMs = 3_000,
                    segments =
                        listOf(
                            AudioSegment(0, -1, "Title. Summary.", 1_000, listOf(0, 50, 100)),
                            AudioSegment(1, 0, "Body.", 2_000, listOf(0, 10)),
                        ),
                ),
                audio,
            )
        }

    @Test
    fun `creating sends the voice, the revision and the parts`() =
        runTest {
            server.enqueue(jsonResponse(generatingJson, code = 202))

            val narrated = SpeechSegment(1, "A $5 wire.", spoken = "A five-dollar wire.", sync = listOf(SyncPoint(5, 14)))
            val audio = repository().create("A1", "talia", 7, listOf(SpeechSegment(-1, "Title."), SpeechSegment(0, "Body."), narrated))

            assertEquals(AudioStatus.Generating, audio.status)
            val request = server.nextRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/articles/A1/audio", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("talia", body["voice"]!!.jsonPrimitive.content)
            assertEquals(7L, body["expected_revision"]!!.jsonPrimitive.long)
            val segments = body["segments"]!!.jsonArray
            assertEquals(-1, segments[0].jsonObject["block"]!!.jsonPrimitive.int)
            assertEquals("Body.", segments[1].jsonObject["text"]!!.jsonPrimitive.content)
            // A plain part sends no script; a narrated one sends what to say and how it lines up.
            assertEquals(setOf("block", "text"), segments[1].jsonObject.keys)
            assertEquals("A five-dollar wire.", segments[2].jsonObject["spoken"]!!.jsonPrimitive.content)
            assertEquals("[[5,14]]", segments[2].jsonObject["sync"].toString())
        }

    @Test
    fun `a refused creation keeps the server's reason`() =
        runTest {
            server.enqueue(jsonResponse("""{"error":"Article changed."}""", code = 409))
            server.enqueue(jsonResponse("""{"error":"Too long."}""", code = 400))
            val repository = repository()

            val conflict = assertThrows<AudioConflictException> { repository.create("A1", "talia", 1, emptyList()) }
            val refused = assertThrows<AudioRequestException> { repository.create("A1", "talia", 1, emptyList()) }

            assertEquals("Article changed.", conflict.message)
            assertEquals("Too long.", refused.message)
        }

    @Test
    fun `downloading keeps one file per segment, skips files it has, and drops older audio`() =
        runTest {
            val repository = repository()
            val older = File(folder.root, "audio/A1/500/0.mp3").apply { parentFile!!.mkdirs() }
            older.writeText("old")
            val existing = File(folder.root, "audio/A1/1000/0.mp3").apply { parentFile!!.mkdirs() }
            existing.writeText("kept")
            server.enqueue(MockResponse.Builder().body(Buffer().write(byteArrayOf(1, 2, 3))).build())
            val progress = mutableListOf<Int>()

            val files = repository.download("A1", readyAudio()) { progress += it }

            assertEquals(listOf(existing, File(folder.root, "audio/A1/1000/1.mp3")), files)
            assertEquals("kept", existing.readText())
            assertArrayEquals(byteArrayOf(1, 2, 3), files[1].readBytes())
            assertFalse(older.exists())
            assertEquals(listOf(1, 2), progress)
            assertEquals("/api/v1/articles/A1/audio/segments/1", server.nextRequest().url.encodedPath)
        }

    @Test
    fun `deleting removes the audio on the server and on this device`() =
        runTest {
            val file = File(folder.root, "audio/A1/1000/0.mp3").apply { parentFile!!.mkdirs() }
            file.writeText("audio")
            server.enqueue(MockResponse(code = 204))

            repository().delete("A1")

            val request = server.nextRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/articles/A1/audio", request.url.encodedPath)
            assertFalse(File(folder.root, "audio/A1").exists())
        }

    @Test
    fun `an ID that could name another folder is refused`() =
        runTest {
            assertThrows<IOException> { repository().deleteDownloads("../session") }
            assertTrue(folder.root.exists())
        }

    private fun readyAudio(): ArticleAudio =
        ArticleAudio(
            status = AudioStatus.Ready,
            voice = AudioVoiceName("talia", "Talia"),
            createdAt = Instant.ofEpochMilli(1_000),
            characters = 20,
            segmentsTotal = 2,
            segmentsDone = 2,
            error = null,
            durationMs = 3_000,
            segments = listOf(AudioSegment(0, -1, "Title.", 1_000, listOf()), AudioSegment(1, 0, "Body.", 2_000, listOf())),
        )

    private suspend inline fun <reified T : Throwable> assertThrows(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
        }
        throw AssertionError("Expected ${T::class.simpleName}, nothing was thrown")
    }

    private companion object {
        val readyJson =
            """
            {"status":"ready","voice":{"id":"talia","name":"Talia"},"created_at":1000,"characters":30,
             "segments_total":2,"segments_done":2,"duration_ms":3000,
             "segments":[{"index":0,"block":-1,"text":"Title. Summary.","duration_ms":1000,"char_starts_ms":[0,50,100]},
                         {"index":1,"block":0,"text":"Body.","duration_ms":2000,"char_starts_ms":[0,10]}]}
            """.trimIndent()

        val generatingJson =
            """
            {"status":"generating","voice":{"id":"talia","name":"Talia"},"created_at":1000,"characters":11,
             "segments_total":2,"segments_done":0}
            """.trimIndent()
    }
}
