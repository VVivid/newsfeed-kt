package dev.vvivid.newsfeed.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Sends every request to [server], whatever its address, keeping its path and query. Tests use it
 * to give the code under test the real https addresses it requires, on reserved example domains.
 * The address each request was made for travels along in [ORIGINAL_URL_HEADER].
 */
class RouteToMockServer(
    private val server: MockWebServer,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val routed =
            request.url
                .newBuilder()
                .scheme("http")
                .host(server.hostName)
                .port(server.port)
                .build()
        return chain.proceed(
            request
                .newBuilder()
                .url(routed)
                .header(ORIGINAL_URL_HEADER, request.url.toString())
                .build(),
        )
    }

    companion object {
        const val ORIGINAL_URL_HEADER = "X-Test-Original-Url"
    }
}

/** The app's client settings, with every request sent to [server] (see [RouteToMockServer]). */
fun clientRoutedTo(server: MockWebServer): OkHttpClient = newHttpClient().newBuilder().addInterceptor(RouteToMockServer(server)).build()

/** The address a request recorded by [MockWebServer] was made for, before [RouteToMockServer] routed it. */
val RecordedRequest.originalUrl: String?
    get() = headers[RouteToMockServer.ORIGINAL_URL_HEADER]

/** The request body as text. */
val RecordedRequest.bodyText: String
    get() = body?.utf8().orEmpty()

/** The fields of a form-encoded request body. */
fun RecordedRequest.formFields(): Map<String, String> =
    bodyText.split('&').filter { it.isNotEmpty() }.associate { field ->
        val (name, value) = field.split('=', limit = 2)
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

/** The next recorded request; fails instead of waiting forever when there is none. */
fun MockWebServer.nextRequest(): RecordedRequest = checkNotNull(takeRequest(5, TimeUnit.SECONDS)) { "No request arrived" }

fun jsonResponse(
    body: String,
    code: Int = 200,
): MockResponse = MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body)
