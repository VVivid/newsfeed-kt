package dev.vvivid.newsfeed.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * The client every request starts from. The app only talks to the user's server and the identity
 * provider that server names, and both answer directly, so no redirect is ever followed: a redirect
 * would send the request, and for the API its access token, to an address the app did not choose.
 * There is no cookie jar and no HTTP cache, so nothing a server sends is kept outside the app's own
 * session file.
 */
fun newHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * Reads JSON from the server and its identity provider. Unknown keys are ignored so that servers can
 * add fields; every field the app reads keeps its declared type, and required ones must be present.
 */
val ServerJson = Json { ignoreUnknownKeys = true }

/**
 * Sends [request] and passes the response to [read] on [ioDispatcher], where blocking body reads
 * belong, then closes it. Cancelling the coroutine cancels the call, so a superseded request stops.
 */
suspend fun <T> OkHttpClient.send(
    request: Request,
    ioDispatcher: CoroutineDispatcher,
    read: (Response) -> T,
): T = withContext(ioDispatcher) { newCall(request).await().use(read) }

/** Enqueues this call and suspends until its response headers arrive. The caller closes the response. */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    // A response that arrives after cancellation is closed instead of leaked.
                    continuation.resume(response) { _, unused, _ -> unused.close() }
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }
            },
        )
    }

/** The body as UTF-8 text. Fails if it is longer than [maxBytes], which no valid answer is. */
fun Response.bodyText(maxBytes: Long): String = bodyAtMost(maxBytes).readUtf8()

/** The body's bytes. Fails if there are more than [maxBytes], which no valid answer has. */
fun Response.bodyBytes(maxBytes: Long): ByteArray = bodyAtMost(maxBytes).readByteArray()

private fun Response.bodyAtMost(maxBytes: Long): Buffer {
    val source = body.source()
    // request() buffers up to the given number of bytes and says whether that many arrived.
    if (source.request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
    return source.buffer
}

/** A response body was longer than any valid answer can be. The connection itself worked. */
class ResponseTooLargeException(
    maxBytes: Long,
) : IOException("The response is larger than $maxBytes bytes")

/** Whether this URL has the same scheme, host and port as [origin], the same-origin rule of browsers. */
fun HttpUrl.hasOrigin(origin: HttpUrl): Boolean = scheme == origin.scheme && host == origin.host && port == origin.port

/** The host, followed by the port when it is not the scheme's default, as people write addresses. */
val HttpUrl.hostWithPort: String
    get() = if (port == HttpUrl.defaultPort(scheme)) host else "$host:$port"
