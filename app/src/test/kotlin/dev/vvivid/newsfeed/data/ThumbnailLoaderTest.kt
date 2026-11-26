package dev.vvivid.newsfeed.data

import android.graphics.Bitmap
import dev.vvivid.newsfeed.net.newHttpClient
import dev.vvivid.newsfeed.net.nextRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** Runs with Robolectric for Android's bitmaps and LruCache. */
@RunWith(RobolectricTestRunner::class)
class ThumbnailLoaderTest {
    private val server = MockWebServer()
    private lateinit var loader: ThumbnailLoader

    @Before
    fun startServer() {
        server.start()
        loader = ThumbnailLoader(newHttpClient(), server.url("/").toString().trimEnd('/'), Dispatchers.IO)
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a thumbnail comes from the server once, then from memory`() =
        runTest {
            val article = testArticle("A1").copy(thumbnailKey = "hash-1")
            server.enqueue(imageResponse())

            val loaded = loader.load(article)

            assertNotNull(loaded)
            assertSame(loaded, loader.load(article))
            assertSame(loaded, loader.cached(article))
            assertEquals(1, server.requestCount)
            val request = server.nextRequest()
            assertEquals("/thumbnails/A1", request.url.encodedPath)
            assertNull(request.url.queryParameter("trashed"))
        }

    @Test
    fun `the thumbnail of an article in Trash is asked for in Trash`() =
        runTest {
            server.enqueue(imageResponse())

            loader.load(testArticle("A2", trashedHoursAgo = 1).copy(thumbnailKey = "hash-2"))

            assertEquals("true", server.nextRequest().url.queryParameter("trashed"))
        }

    @Test
    fun `an article without a thumbnail, or one the server lacks, shows its cover`() =
        runTest {
            assertNull(loader.load(testArticle("A3")))
            assertEquals(0, server.requestCount)

            val missing = testArticle("A4").copy(thumbnailKey = "hash-4")
            server.enqueue(MockResponse(code = 404, body = "Not found"))
            assertNull(loader.load(missing))
            // Not asked for again in this session.
            assertNull(loader.load(missing))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `after the session ends nothing stays in memory`() =
        runTest {
            val article = testArticle("A5").copy(thumbnailKey = "hash-5")
            server.enqueue(imageResponse())
            loader.load(article)

            loader.clear()

            assertNull(loader.cached(article))
        }

    private fun imageResponse(): MockResponse {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return MockResponse
            .Builder()
            .code(200)
            .headers(headersOf("Content-Type", "image/png"))
            .body(Buffer().write(png))
            .build()
    }
}
