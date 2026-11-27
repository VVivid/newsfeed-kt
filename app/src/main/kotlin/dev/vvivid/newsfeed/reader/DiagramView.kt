package dev.vvivid.newsfeed.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.vvivid.newsfeed.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A diagram drawn into [image]; [widthDp] x [heightDp] is its natural size. */
private class DrawnDiagram(
    val image: ImageBitmap,
    val widthDp: Int,
    val heightDp: Int,
)

/** What Mermaid made of a diagram so far. */
private sealed interface DiagramState {
    data object Drawing : DiagramState

    /** The page drew the diagram; its PNG is read next. */
    data class Ready(
        val widthDp: Int,
        val heightDp: Int,
    ) : DiagramState

    class Drawn(
        val diagram: DrawnDiagram,
    ) : DiagramState

    data object Failed : DiagramState
}

/**
 * A ```` ```mermaid ```` block: a picture of the diagram that fits the article's width, in Mermaid's
 * hand-drawn style with readable labels. Tapping it opens [DiagramViewer] to zoom and pan.
 *
 * Mermaid is JavaScript, so it runs once per diagram in a hidden WebView that is locked down for
 * untrusted input (the owner agreed to this exception to the no-script rule for diagrams): only the
 * Mermaid bundle and `render.js` from the app's assets load, network loads are blocked, there is no
 * JavaScript bridge, no navigation and no file or content access, and Mermaid runs with
 * `securityLevel: 'strict'`. `render.js` draws the diagram into a PNG and says so in the page title;
 * the app reads the PNG with evaluateJavascript and shows it as an ordinary image. Pictures are
 * cached, so scrolling back does not draw a diagram again. A diagram Mermaid cannot read shows its
 * source as code.
 */
@Composable
internal fun DiagramView(
    source: String,
    modifier: Modifier = Modifier,
) {
    var state by remember(source) {
        mutableStateOf<DiagramState>(DrawnDiagrams.get(source)?.let(DiagramState::Drawn) ?: DiagramState.Drawing)
    }
    var webView by remember(source) { mutableStateOf<WebView?>(null) }
    val current = state
    if (current is DiagramState.Ready) {
        LaunchedEffect(current) {
            val view = webView
            val png = view?.let { readPng(it) }
            val drawn = png?.let { decodePng(it, current) }
            if (drawn != null) DrawnDiagrams.put(source, drawn)
            state = if (drawn != null) DiagramState.Drawn(drawn) else DiagramState.Failed
        }
    }
    when (current) {
        DiagramState.Failed -> {
            CodeBlockView(source, modifier)
        }

        is DiagramState.Drawn -> {
            DiagramCard(current.diagram, modifier)
        }

        DiagramState.Drawing, is DiagramState.Ready -> {
            DiagramFrame(modifier) {
                Box(modifier = Modifier.fillMaxWidth().height(DrawingHeight), contentAlignment = Alignment.Center) {
                    // The page stays on screen but shows nothing (a covered WebView may stop drawing,
                    // which stalls Mermaid); the card shows progress until the picture is ready.
                    AndroidView(
                        factory = { context ->
                            diagramWebView(context, source) { reported -> if (state == DiagramState.Drawing) state = reported }
                                .also { webView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.destroy() },
                    )
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** The white, outlined card a diagram sits on in both app themes (Mermaid's theme is light). */
@Composable
private fun DiagramFrame(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val description = stringResource(R.string.article_diagram)
    val openLabel = stringResource(R.string.article_diagram_open)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = description }
                .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/** The picture, centered and scaled down to fit; never wider than drawn, never taller than [MaxInlineHeight]. */
@Composable
private fun DiagramCard(
    diagram: DrawnDiagram,
    modifier: Modifier,
) {
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    DiagramFrame(modifier, onClick = { viewerOpen = true }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = diagram.widthDp.toFloat() / diagram.heightDp
                var width: Dp = min(maxWidth, diagram.widthDp.dp)
                var height: Dp = width / ratio
                if (height > MaxInlineHeight) {
                    height = MaxInlineHeight
                    width = height * ratio
                }
                Image(
                    bitmap = diagram.image,
                    contentDescription = null,
                    modifier = Modifier.size(width, height),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            }
            // Says how to see the whole diagram; the article's text stays the main thing on the page.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_zoom_in),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.DarkGray,
                )
                Text(
                    text = stringResource(R.string.article_diagram_tap_hint),
                    modifier = Modifier.padding(start = 4.dp),
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
    if (viewerOpen) DiagramViewer(diagram, onClose = { viewerOpen = false })
}

/** The whole screen for one diagram: pinch to zoom, drag to pan, double-tap to zoom in or back out. */
@Composable
private fun DiagramViewer(
    diagram: DrawnDiagram,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var viewSize by remember { mutableStateOf(IntSize.Zero) }

            /** Zooms to [newScale] keeping the content under [focus] (a point in the view) in place. */
            val density = LocalDensity.current.density

            /**
             * The zoom at which the diagram shows at its natural size, where its labels read like body
             * text; 1 when that already fits the screen.
             */
            fun readableZoom(): Float {
                val ratio = diagram.widthDp.toFloat() / diagram.heightDp
                val fittedWidth = minOf(viewSize.width.toFloat(), viewSize.height * ratio)
                return (diagram.widthDp * density / fittedWidth).coerceIn(MIN_ZOOM, MAX_ZOOM)
            }

            fun zoomAround(
                focus: Offset,
                newScale: Float,
                pan: Offset = Offset.Zero,
            ) {
                val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
                val target = newScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
                val moved = (focus - center) - (focus - center - offset) * (target / scale) + pan
                // The fitted picture may pan only until its edges reach the screen's edges.
                val ratio = diagram.widthDp.toFloat() / diagram.heightDp
                val fittedWidth = minOf(viewSize.width.toFloat(), viewSize.height * ratio)
                val fittedHeight = fittedWidth / ratio
                val maxX = maxOf(0f, (fittedWidth * target - viewSize.width) / 2f)
                val maxY = maxOf(0f, (fittedHeight * target - viewSize.height) / 2f)
                offset = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                scale = target
            }
            var opened by remember { mutableStateOf(false) }
            LaunchedEffect(viewSize) {
                if (opened || viewSize == IntSize.Zero) return@LaunchedEffect
                opened = true
                // Open readable, at the diagram's start: its left end, or its top for a tall one.
                // Zooming around the far corner pins that corner's side of the picture to the screen.
                zoomAround(Offset.Zero, readableZoom(), pan = Offset(Float.MAX_VALUE / 4, Float.MAX_VALUE / 4))
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewSize = it }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { tap ->
                                    val readable = readableZoom().takeIf { it > 1.05f } ?: DOUBLE_TAP_ZOOM
                                    zoomAround(tap, if (scale > 1.05f) MIN_ZOOM else readable)
                                },
                            )
                        }.pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ -> zoomAround(centroid, scale * zoom, pan) }
                        },
            ) {
                Image(
                    bitmap = diagram.image,
                    contentDescription = stringResource(R.string.article_diagram),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
                // Controls sit on small light backgrounds so the diagram under them does not hide them.
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
                    shape = RoundedCornerShape(50),
                    color = ControlBackground,
                ) {
                    Text(
                        text = stringResource(R.string.article_diagram_zoom_hint),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color.DarkGray,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                    shape = RoundedCornerShape(50),
                    color = ControlBackground,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.article_diagram_close),
                            tint = Color.Black,
                        )
                    }
                }
            }
        }
    }
}

private val ControlBackground = Color(0xF0F2F2F2)

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Space a diagram takes while Mermaid draws it. */
private val DrawingHeight = 160.dp

/** A tall diagram's picture is scaled down to this height in the article; the viewer shows it whole. */
private val MaxInlineHeight = 420.dp

private const val ASSET_BASE = "file:///android_asset/mermaid/"

/** Drawn pictures by diagram source, bounded by their size in bytes. */
private object DrawnDiagrams : LruCache<String, DrawnDiagram>(48 * 1024 * 1024) {
    override fun sizeOf(
        key: String,
        value: DrawnDiagram,
    ): Int = value.image.width * value.image.height * 4
}

/** Reads the PNG data URL that `render.js` left in `window.diagramPng`. */
private suspend fun readPng(view: WebView): String? =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript("window.diagramPng || null") { json -> continuation.resumeWith(Result.success(json)) }
    }

/** The picture in [json], the JSON string evaluateJavascript returned, or null if it is not a PNG. */
private suspend fun decodePng(
    json: String,
    size: DiagramState.Ready,
): DrawnDiagram? =
    withContext(Dispatchers.Default) {
        val dataUrl = runCatching { Json.decodeFromString<String?>(json) }.getOrNull() ?: return@withContext null
        if (!dataUrl.startsWith(PNG_DATA_URL)) return@withContext null
        val bytes = runCatching { Base64.decode(dataUrl.substring(PNG_DATA_URL.length), Base64.DEFAULT) }.getOrNull()
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@withContext null
        DrawnDiagram(bitmap.asImageBitmap(), size.widthDp, size.heightDp)
    }

private const val PNG_DATA_URL = "data:image/png;base64,"

@SuppressLint(
    // Mermaid needs JavaScript; the page runs only bundled assets, with no bridge or network.
    "SetJavaScriptEnabled",
)
private fun diagramWebView(
    context: Context,
    source: String,
    onState: (DiagramState) -> Unit,
): WebView =
    WebView(context).apply {
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
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setBackgroundColor(android.graphics.Color.WHITE)
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
        // Anything else, such as a URL inside a diagram, gets an empty answer. The PNG is drawn
        // from a blob: URL, which never reaches this method.
        return WebResourceResponse("text/plain", "utf-8", null)
    }
}

/** `render.js` reports "ready:<width>x<height>" in CSS pixels, which are dp here, or "error". */
private fun parseDiagramTitle(title: String?): DiagramState? {
    if (title == "error") return DiagramState.Failed
    val size = title?.removePrefix("ready:")?.takeIf { it != title } ?: return null
    val width = size.substringBefore('x').toIntOrNull() ?: return null
    val height = size.substringAfter('x').toIntOrNull() ?: return null
    return if (width > 0 && height > 0) DiagramState.Ready(width, height) else DiagramState.Failed
}

/** The page for [source]. The source is HTML-escaped text that render.js reads, never markup. */
internal fun diagramPage(source: String): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>html,body{margin:0;padding:0;background:#fff}#source{display:none}body *{visibility:hidden}</style>
    </head><body>
    <pre id="source">${escapeHtml(source)}</pre>
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
