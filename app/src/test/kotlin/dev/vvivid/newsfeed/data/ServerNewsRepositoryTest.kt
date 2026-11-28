package dev.vvivid.newsfeed.data

import dev.vvivid.newsfeed.auth.SessionEndedException
import dev.vvivid.newsfeed.net.bodyText
import dev.vvivid.newsfeed.net.jsonResponse
import dev.vvivid.newsfeed.net.newHttpClient
import dev.vvivid.newsfeed.net.nextRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ServerNewsRepositoryTest {
    private val server = MockWebServer()
    private var keysMade = 0

    /** While true, the next response is lost after the server handled the request. */
    private var loseNextResponse = false

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a page asks for the shelf, the page size and the cursor`() =
        runTest {
            repeat(3) { server.enqueue(jsonResponse("""{"articles":[]}""")) }
            val repository = repository()

            repository.listArticles(Shelf.Active)
            repository.listArticles(Shelf.Archive, cursor = "cursor-2")
            repository.listArticles(Shelf.Trash)

            val active = server.nextRequest().url
            assertEquals("/api/v1/articles", active.encodedPath)
            assertEquals("50", active.queryParameter("limit"))
            assertNull(active.queryParameter("archived"))
            assertNull(active.queryParameter("trashed"))
            val archive = server.nextRequest().url
            assertEquals("true", archive.queryParameter("archived"))
            assertEquals("cursor-2", archive.queryParameter("cursor"))
            assertEquals("true", server.nextRequest().url.queryParameter("trashed"))
        }

    @Test
    fun `a filtered page repeats the tag parameter, and tags are listed per shelf`() =
        runTest {
            server.enqueue(jsonResponse("""{"articles":[]}"""))
            server.enqueue(jsonResponse("""{"tags":[{"tag":"explore","count":3},{"tag":"leetcode","count":1}]}"""))
            server.enqueue(jsonResponse("""{"tags":null}"""))
            val repository = repository()

            repository.listArticles(Shelf.Active, tags = setOf("leetcode", "explore"))
            val tags = repository.listTags(Shelf.Archive)
            val none = repository.listTags(Shelf.Trash)

            val page = server.nextRequest().url
            assertEquals(listOf("explore", "leetcode"), page.queryParameterValues("tag"))
            val tagRequest = server.nextRequest().url
            assertEquals("/api/v1/tags", tagRequest.encodedPath)
            assertEquals("true", tagRequest.queryParameter("archived"))
            assertEquals(listOf(TagCount("explore", 3), TagCount("leetcode", 1)), tags)
            assertEquals("true", server.nextRequest().url.queryParameter("trashed"))
            assertEquals(emptyList<TagCount>(), none)
        }

    @Test
    fun `an article the agent wrote has tags and may have no address`() =
        runTest {
            server.enqueue(
                jsonResponse(
                    """
                    {"articles":[
                      {"id":"G1","title":"Two Sum","summary":"Explained.","revision":1,"created_at":1764000000000,
                       "tags":["generated","leetcode"]}
                    ]}
                    """.trimIndent(),
                ),
            )

            val article = repository().listArticles(Shelf.Active).articles.single()

            assertNull(article.url)
            assertNull(article.sourceHost)
            assertEquals(listOf("generated", "leetcode"), article.tags)
            assertTrue(article.isGenerated)
        }

    @Test
    fun `a page becomes articles, and fields the app does not use are ignored`() =
        runTest {
            server.enqueue(
                jsonResponse(
                    """
                    {"articles":[
                      {"id":"A1","url":"https://news.example.com/one","title":"One","summary":"First.","revision":3,
                       "created_at":1764000000000,"updated_at":1764000500000,"archived_at":1764000400000,
                       "thumbnail_url":"https://images.example.com/one.jpg","thumbnail_status":"stored",
                       "thumbnail_hash":"abc123","thumbnail_width":640,"thumbnail_height":360,"thumbnail_bytes":2048,
                       "a_future_field":{"nested":[1,2]}},
                      {"id":"A2","url":"https://news.example.com/two","title":"Two","summary":"Second.","revision":1,
                       "created_at":1763000000000,"updated_at":1763000000000,"thumbnail_status":"unavailable"}
                    ],"next_cursor":"next-page"}
                    """.trimIndent(),
                ),
            )

            val page = repository().listArticles(Shelf.Archive)

            assertEquals("next-page", page.nextCursor)
            assertEquals(
                Article(
                    id = "A1",
                    url = "https://news.example.com/one",
                    title = "One",
                    summary = "First.",
                    revision = 3,
                    createdAt = Instant.ofEpochMilli(1764000000000),
                    archivedAt = Instant.ofEpochMilli(1764000400000),
                    trashedAt = null,
                    thumbnailKey = "abc123",
                ),
                page.articles[0],
            )
            assertNull(page.articles[1].thumbnailKey)
            assertNull(page.articles[1].archivedAt)
        }

    @Test
    fun `an empty page and a last page`() =
        runTest {
            // The server encodes an empty list as null and leaves out the cursor of a last page.
            server.enqueue(jsonResponse("""{"articles":null}"""))

            assertEquals(ArticlePage(emptyList(), nextCursor = null), repository().listArticles(Shelf.Trash))
        }

    @Test
    fun `an article is read with its saved body, from Trash when asked`() =
        runTest {
            server.enqueue(jsonResponse(articleJson(id = "A1", trashedAt = 1764000600000, contentMarkdown = "# Saved\\n\\nBody.")))

            val detail = repository().getArticle("A1", inTrash = true)

            val request = server.nextRequest()
            assertEquals("/api/v1/articles/A1", request.url.encodedPath)
            assertEquals("true", request.url.queryParameter("trashed"))
            assertEquals("# Saved\n\nBody.", detail.contentMarkdown)
            assertEquals(Shelf.Trash, detail.article.shelf)
        }

    @Test
    fun `an article without a saved body has none`() =
        runTest {
            server.enqueue(jsonResponse(articleJson(id = "A1")))

            val detail = repository().getArticle("A1", inTrash = false)
            assertNull(detail.contentMarkdown)
            assertEquals(emptyList<NarrationPart>(), detail.narration)
            assertNull(server.nextRequest().url.queryParameter("trashed"))
        }

    @Test
    fun `an article is read with its narration script`() =
        runTest {
            val narration = ""","narration":[{"text":"Title of A1","say":"Title of A one."},{"describe":"code","say":"Some code."}]"""
            server.enqueue(jsonResponse(articleJson(id = "A1", contentMarkdown = "```go\nx\n```").dropLast(1) + narration + "}"))

            assertEquals(
                listOf(NarrationPart("Title of A1", null, "Title of A one."), NarrationPart(null, "code", "Some code.")),
                repository().getArticle("A1", inTrash = false).narration,
            )
        }

    @Test
    fun `each state change posts the expected revision and a key, and nothing else`() =
        runTest {
            repeat(4) { server.enqueue(jsonResponse(articleJson(id = "A1", revision = 8))) }
            val repository = repository()

            repository.setArchived("A1", expectedRevision = 7, archived = true)
            repository.setArchived("A1", expectedRevision = 7, archived = false)
            repository.moveToTrash("A1", expectedRevision = 7)
            repository.restoreFromTrash("A1", expectedRevision = 7)

            val requests = List(4) { server.nextRequest() }
            assertStateChangeRequest(requests.first())
            assertEquals(
                listOf(
                    "/api/v1/articles/A1/archive",
                    "/api/v1/articles/A1/restore",
                    "/api/v1/articles/A1/trash",
                    "/api/v1/articles/A1/restore-trash",
                ),
                requests.map { it.url.encodedPath },
            )
        }

    @Test
    fun `the server's answers become the app's errors`() =
        runTest {
            val repository = repository()
            val cases =
                listOf(
                    409 to ArticleConflictException::class.java,
                    404 to ArticleNotFoundException::class.java,
                    403 to PermissionDeniedException::class.java,
                    401 to SessionEndedException::class.java,
                    503 to UnexpectedResponseException::class.java,
                )
            for ((status, expected) in cases) {
                server.enqueue(jsonResponse("""{"error":"Synthetic error."}""", code = status))
                val failure = failureOf { repository.moveToTrash("A1", expectedRevision = 1) }
                assertEquals("HTTP $status", expected, failure.javaClass)
            }
            server.enqueue(jsonResponse("""{"articles":[{"id":"A1"}]}"""))
            assertEquals(UnexpectedResponseException::class.java, failureOf { repository.listArticles(Shelf.Active) }.javaClass)
        }

    @Test
    fun `a change whose answer was lost is sent again with the same key`() =
        runTest {
            val repository = repository()
            server.enqueue(jsonResponse(articleJson(id = "A1", revision = 2, archivedAt = 1764000400000)))
            server.enqueue(jsonResponse(articleJson(id = "A1", revision = 2, archivedAt = 1764000400000)))
            loseNextResponse = true

            failureOf { repository.setArchived("A1", expectedRevision = 1, archived = true) }
            val archived = repository.setArchived("A1", expectedRevision = 1, archived = true)

            assertEquals(Shelf.Archive, archived.shelf)
            val firstKey = server.nextRequest().idempotencyKey()
            assertEquals(firstKey, server.nextRequest().idempotencyKey())

            // The change is confirmed, so the next one, even the same change again, is a new intent.
            server.enqueue(jsonResponse(articleJson(id = "A1", revision = 2, archivedAt = 1764000400000)))
            repository.setArchived("A1", expectedRevision = 1, archived = true)
            assertNotEquals(firstKey, server.nextRequest().idempotencyKey())
        }

    @Test
    fun `a change the server refused is a new request next time`() =
        runTest {
            val repository = repository()
            server.enqueue(jsonResponse("""{"error":"Article changed."}""", code = 409))
            server.enqueue(jsonResponse(articleJson(id = "A1", revision = 2, trashedAt = 1764000600000)))

            failureOf { repository.moveToTrash("A1", expectedRevision = 1) }
            repository.moveToTrash("A1", expectedRevision = 1)

            assertNotEquals(server.nextRequest().idempotencyKey(), server.nextRequest().idempotencyKey())
        }

    private fun repository(): ServerNewsRepository {
        val client =
            newHttpClient()
                .newBuilder()
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (loseNextResponse) {
                        loseNextResponse = false
                        response.close()
                        throw IOException("Connection reset after the server handled the request")
                    }
                    response
                }.build()
        return repository(client)
    }

    private fun repository(client: OkHttpClient) =
        ServerNewsRepository(
            httpClient = client,
            serverOrigin = server.url("/").toString().trimEnd('/'),
            ioDispatcher = Dispatchers.IO,
            newIdempotencyKey = { "test-key-${++keysMade}" },
        )

    private fun assertStateChangeRequest(request: RecordedRequest) {
        assertEquals("POST", request.method)
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
        val body = Json.parseToJsonElement(request.bodyText).jsonObject
        assertEquals(setOf("expected_revision", "idempotency_key"), body.keys)
        assertEquals("7", body["expected_revision"].toString())
    }

    private fun RecordedRequest.idempotencyKey(): String = (Json.parseToJsonElement(bodyText) as JsonObject)["idempotency_key"].toString()

    /** The exception [block] fails with. An AssertionError is not an Exception, so it is not caught. */
    private suspend fun failureOf(block: suspend () -> Unit): Exception =
        try {
            block()
            throw AssertionError("The call should have failed")
        } catch (e: Exception) {
            e
        }

    private fun articleJson(
        id: String,
        revision: Long = 1,
        archivedAt: Long? = null,
        trashedAt: Long? = null,
        contentMarkdown: String? = null,
    ): String {
        val optional =
            listOfNotNull(
                archivedAt?.let { ""","archived_at":$it""" },
                trashedAt?.let { ""","trashed_at":$it""" },
                contentMarkdown?.let { ""","content_markdown":"$it"""" },
            ).joinToString("")
        return """{"id":"$id","url":"https://news.example.com/articles/$id","title":"Title of $id","summary":"Summary of $id.",""" +
            """"revision":$revision,"created_at":1764000000000,"updated_at":1764000000000$optional}"""
    }
}
