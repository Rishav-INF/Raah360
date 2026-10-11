package com.raah360.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.raah360.core.Route
import com.raah360.core.Vec2
import com.raah360.core.Venue
import com.raah360.core.VisualPin
import java.util.Locale

/**
 * Talks to the building model in assets/map3d/index.html (three.js, bundled offline).
 * Calls made before the page is ready are queued and replayed in order.
 */
class Map3DController {
    internal var web: WebView? = null
    private var ready = false
    private val pending = ArrayList<String>()

    internal fun onReady() {
        ready = true
        pending.forEach { web?.evaluateJavascript(it, null) }
        pending.clear()
    }

    private fun js(code: String) {
        if (ready) web?.evaluateJavascript(code, null) else pending += code
    }

    private fun n(d: Double) = String.format(Locale.US, "%.3f", d)
    private fun q(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** The building to draw: the surveyed atrium has its own model; any other is built from its map data. */
    fun setVenue(v: Venue, floor: Int) {
        val nodes = v.nodes.filter { it.floor == floor }
        val ids = nodes.map { it.id }.toSet()
        js("R3.setVenue({id:${q(v.id)},fl:$floor,nodes:[" + nodes.joinToString(",") { "{i:${q(it.id)},p:[${n(it.pos.x)},${n(it.pos.y)}]}" } +
            "],edges:[" + v.edges.filter { it.a in ids && it.b in ids }.joinToString(",") { "[${q(it.a)},${q(it.b)}]" } +
            "],pins:[" + v.pins.filter { it.floor == floor }.joinToString(",") {
                "{p:[${n(it.pos.x)},${n(it.pos.y)}],f:${it.facingDeg?.let(::n) ?: "null"},c:${q(it.category)}}"
            } + "]})")
    }

    fun setPlaces(pins: List<VisualPin>) =
        js("R3.setPlaces([" + pins.joinToString(",") { "{id:${q(it.id)},name:${q(it.name)},x:${n(it.pos.x)},y:${n(it.pos.y)},f:${it.floor}}" } + "])")

    fun setStart(p: Vec2?, floor: Int = 0) = js(if (p == null) "R3.setStart(null)" else "R3.setStart(${n(p.x)},${n(p.y)},$floor)")
    fun setDest(pinId: String?) = js(if (pinId == null) "R3.setDest(null)" else "R3.setDest(${q(pinId)})")
    fun setRoute(r: Route?) =
        js(if (r == null) "R3.setRoute(null)" else "R3.setRoute([" + r.points.joinToString(",") { "[${n(it.pos.x)},${n(it.pos.y)},${it.floor}]" } + "])")
    fun setProgress(s: Double) = js("R3.setProgress(${n(s)})")
    /** Path walked the wrong way, drawn red over the green route; null clears it. */
    fun setTrail(pts: List<Vec2>?, floor: Int = 0) =
        js(if (pts.isNullOrEmpty()) "R3.setTrail(null)" else "R3.setTrail([" + pts.joinToString(",") { "[${n(it.x)},${n(it.y)}]" } + "],$floor)")
    fun setWrong(wrong: Boolean) = js("R3.setWrong($wrong)")
    fun setUser(p: Vec2?, headingDeg: Double = 0.0, floor: Int = 0) =
        js(if (p == null) "R3.setUser(null)" else "R3.setUser(${n(p.x)},${n(p.y)},${n(headingDeg)},$floor)")
    /** Which storey to look at (the ground floor hides the floors above it). */
    fun setFloor(floor: Int) = js("R3.setFloor($floor)")

    /** "overview" | "start" | "route" | "follow"; [headingDeg] = which way to look at the start before there's a route. */
    fun view(mode: String, headingDeg: Double? = null) =
        js("R3.view(${q(mode)}" + (headingDeg?.let { ",${n(it)}" } ?: "") + ")")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Map3D(
    controller: Map3DController,
    modifier: Modifier = Modifier,
    onTap: (Vec2) -> Unit,
    onGesture: () -> Unit = {},
    /** False while the AR camera is showing: a visible WebView would cover the camera picture. */
    visible: Boolean = true,
) {
    val tap = rememberUpdatedState(onTap)
    val gesture = rememberUpdatedState(onGesture)
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AColor.parseColor("#0E1116"))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        Log.i("Map3D", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                        return true
                    }
                }
                // JS calls arrive on a binder thread: hop to the main thread before touching UI state
                addJavascriptInterface(object {
                    @JavascriptInterface fun onReady() = main.post { controller.onReady() }.let { }
                    @JavascriptInterface fun onTap(x: Double, y: Double) = main.post { tap.value(Vec2(x, y)) }.let { }
                    @JavascriptInterface fun onGesture() = main.post { gesture.value() }.let { }
                }, "Android")
                controller.web = this
                loadUrl("file:///android_asset/map3d/index.html")
            }
        },
        update = { it.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE },
    )
    DisposableEffect(controller) {
        onDispose { controller.web?.destroy(); controller.web = null }
    }
}
