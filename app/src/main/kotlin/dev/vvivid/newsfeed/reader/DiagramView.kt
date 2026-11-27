package dev.vvivid.newsfeed.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.vvivid.newsfeed.R

/** What a diagram's WebView reported: nothing yet, its drawn size in dp, or that it failed. */
private sealed interface DiagramState {
    data object Drawing : DiagramState

    data class Drawn(
        val widthDp: Int,
        val heightDp: Int,
    ) : DiagramState

    data object Failed : DiagramState
}

/**
 * A ```` ```mermaid ```` block drawn by Mermaid in its hand-drawn style.
 *
 * Mermaid is JavaScript, so it runs in a WebView that is locked down for untrusted input (the owner
 * agreed to this exception to the no-script rule for diagrams): only the Mermaid bundle and
 * `render.js` from the app's assets load, network loads are blocked, there is no JavaScript bridge,
 * no navigation and no file or content access. Mermaid runs with `securityLevel: 'strict'`.
 * `render.js` reports the drawn size through the page title.
 *
 * The WebView ignores touches so that the article keeps scrolling over a diagram; a diagram wider
 * than the screen scrolls sideways in Compose. A diagram Mermaid cannot read shows its source.
 */
@Composable
internal fun DiagramView(
    source: String,
    modifier: Modifier = Modifier,
) {
    var state by remember(source) { mutableStateOf<DiagramState>(DiagramState.Drawing) }
    val current = state
    if (current == DiagramState.Failed) {
        CodeBlockView(source, modifier)
        return
    }
    val description = stringResource(R.string.article_diagram)
    Surface(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        // Mermaid's neutral theme is drawn for a light page in both app themes.
        color = androidx.compose.ui.graphics.Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val size =
                when (current) {
                    is DiagramState.Drawn -> Modifier.size(current.widthDp.dp, current.heightDp.dp)
                    else -> Modifier.fillMaxWidth().height(DrawingHeight)
                }
            AndroidView(
                factory = { context -> diagramWebView(context, source) { state = it } },
                modifier = size,
            )
        }
    }
}

/** Space a diagram takes while Mermaid draws it. */
private val DrawingHeight = 120.dp

private const val ASSET_BASE = "file:///android_asset/mermaid/"

@SuppressLint(
    // Mermaid needs JavaScript; the page runs only bundled assets, with no bridge or network.
    "SetJavaScriptEnabled",
    // The WebView takes no touches at all, so there is no click to perform.
    "ClickableViewAccessibility",
)
private fun diagramWebView(
    context: Context,
    source: String,
    onState: (DiagramState) -> Unit,
): WebView =
    object : WebView(context) {
        // Touches go to the article around the diagram, which scrolls it.
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    }.apply {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            blockNetworkImage = true
            domStorageEnabled = false
            setGeolocationEnabled(false)
            setSupportZoom(false)
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setBackgroundColor(Color.TRANSPARENT)
        webViewClient = AssetsOnlyClient
        webChromeClient =
            object : WebChromeClient() {
                override fun onReceivedTitle(
                    view: WebView,
                    title: String?,
                ) {
                    parseDiagramTitle(title)?.let(onState)
                }
            }
        loadDataWithBaseURL(ASSET_BASE, diagramPage(source), "text/html", "utf-8", null)
    }

/** Serves only the diagram's own assets and never navigates anywhere. */
private object AssetsOnlyClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = true

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        // The page itself comes from loadDataWithBaseURL, which reports the base URL.
        if (request.isForMainFrame) return null
        val url = request.url.toString()
        if (url == ASSET_BASE + "mermaid.min.js" || url == ASSET_BASE + "render.js") return null
        // Anything else, such as a URL inside a diagram, gets an empty answer.
        return WebResourceResponse("text/plain", "utf-8", null)
    }
}

/** `render.js` reports "size:<width>x<height>" in CSS pixels, which are dp here, or "error". */
private fun parseDiagramTitle(title: String?): DiagramState? {
    if (title == "error") return DiagramState.Failed
    val size = title?.removePrefix("size:")?.takeIf { it != title } ?: return null
    val width = size.substringBefore('x').toIntOrNull() ?: return null
    val height = size.substringAfter('x').toIntOrNull() ?: return null
    return if (width > 0 && height > 0) DiagramState.Drawn(width, height) else DiagramState.Failed
}

/** The page for [source]. The source is HTML-escaped text that render.js reads, never markup. */
internal fun diagramPage(source: String): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>html,body{margin:0;padding:0;background:transparent}#source{display:none}#diagram-container{display:inline-block}</style>
    </head><body>
    <pre id="source">${escapeHtml(source)}</pre>
    <div id="diagram-container"></div>
    <script src="mermaid.min.js"></script>
    <script src="render.js"></script>
    </body></html>
    """.trimIndent()

internal fun escapeHtml(text: String): String =
    buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
