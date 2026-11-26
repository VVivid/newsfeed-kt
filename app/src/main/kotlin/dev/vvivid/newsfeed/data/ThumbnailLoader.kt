package dev.vvivid.newsfeed.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.vvivid.newsfeed.net.bodyBytes
import dev.vvivid.newsfeed.net.send
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Collections

/**
 * Loads article thumbnails from the signed-in server (`GET /thumbnails/{id}`), with the session's
 * credentials from [httpClient], and keeps recently shown ones in memory.
 *
 * The server makes these small JPEGs itself, so they always come from the server's own origin and
 * never from the article's site. Nothing is written to disk: when the session ends, its images go
 * with it.
 */
class ThumbnailLoader(
    private val httpClient: OkHttpClient,
    serverOrigin: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val thumbnailsUrl: HttpUrl =
        serverOrigin
            .toHttpUrl()
            .newBuilder()
            .addPathSegment("thumbnails")
            .build()

    private val memoryCache =
        object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    /** Images the server said it does not have, so they are not asked for again in this session. */
    private val missing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** The article's thumbnail if it is already in memory. */
    fun cached(article: Article): Bitmap? = cacheKey(article)?.let(memoryCache::get)

    /**
     * The article's thumbnail, or null when it has none or it could not be loaded; the screen then
     * shows the fallback cover. A failure is not an error worth reporting: the cover is still shown.
     */
    suspend fun load(article: Article): Bitmap? {
        val key = cacheKey(article) ?: return null
        memoryCache.get(key)?.let { return it }
        if (key in missing) return null

        val url =
            thumbnailsUrl
                .newBuilder()
                .addPathSegment(article.id)
                // Like the API, the server only looks in Trash when asked to.
                .apply { if (article.shelf == Shelf.Trash) addQueryParameter("trashed", "true") }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "image/jpeg")
                .build()
        val bitmap =
            try {
                httpClient.send(request, ioDispatcher) { response ->
                    when (response.code) {
                        200 -> {
                            decodeThumbnail(response.bodyBytes(MAX_THUMBNAIL_BYTES))
                        }

                        404 -> {
                            missing += key
                            null
                        }

                        else -> {
                            null
                        }
                    }
                }
            } catch (e: IOException) {
                null
            }
        if (bitmap != null) memoryCache.put(key, bitmap)
        return bitmap
    }

    /** Drops every image, when the session ends. */
    fun clear() {
        memoryCache.evictAll()
        missing.clear()
    }

    private fun cacheKey(article: Article): String? = article.thumbnailKey?.let { "${article.id}/$it" }

    private companion object {
        /** About fifty of the server's thumbnails, which are at most 640 pixels wide. */
        const val MAX_CACHE_BYTES = 16 * 1024 * 1024

        /** The server keeps thumbnails under 100 KiB; anything far larger is not one of them. */
        const val MAX_THUMBNAIL_BYTES = 2L * 1024 * 1024
    }
}

/** Larger images are decoded at a fraction of their size; the server's own are at most 640 pixels. */
private const val MAX_DECODED_EDGE_PIXELS = 1280

/** Decodes a downloaded image, scaled down by powers of two until it fits [MAX_DECODED_EDGE_PIXELS]. */
private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODED_EDGE_PIXELS) sampleSize *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}
