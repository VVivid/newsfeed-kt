package dev.vvivid.newsfeed.browser

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Opens [url], an article's original or a link in it, in a Custom Tab of the user's browser: a
 * browser, never a WebView, so the page runs without access to the app. Only `http` and `https`
 * addresses are opened. Returns false when nothing was opened, because the address is not a web
 * page or no browser is installed.
 *
 * This is the simple form; tracking whether an article was read to the end comes with the in-app
 * browser.
 */
fun openInBrowser(
    context: Context,
    url: String,
): Boolean {
    val uri = url.toUri()
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    return try {
        CustomTabsIntent
            .Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
