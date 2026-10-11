package com.raah360.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.raah360.core.Pose
import com.raah360.core.Route
import com.raah360.core.Vec2
import com.raah360.core.Venue
import com.raah360.core.VisualPin

/** Fits a floor of the venue into a canvas; y goes up on the plan. */
class MapProjection(venue: Venue, floor: Int, private val size: Size, padPx: Float) {
    private val minX: Double
    private val maxX: Double
    private val minY: Double
    private val maxY: Double
    val scale: Float
    private val ox: Float
    private val oy: Float

    init {
        val pts = venue.nodes.filter { it.floor == floor }.map { it.pos } +
            venue.landmarks.filter { it.floor == floor }.map { it.pos } +
            venue.pins.filter { it.floor == floor }.map { it.pos }
        minX = (pts.minOfOrNull { it.x } ?: 0.0) - 1.0
        maxX = (pts.maxOfOrNull { it.x } ?: 10.0) + 1.0
        minY = (pts.minOfOrNull { it.y } ?: 0.0) - 1.0
        maxY = (pts.maxOfOrNull { it.y } ?: 10.0) + 1.0
        val w = (maxX - minX).coerceAtLeast(1.0)
        val h = (maxY - minY).coerceAtLeast(1.0)
        scale = minOf((size.width - 2 * padPx) / w.toFloat(), (size.height - 2 * padPx) / h.toFloat())
        ox = (size.width - w.toFloat() * scale) / 2f
        oy = (size.height - h.toFloat() * scale) / 2f
    }

    fun toScreen(p: Vec2) = Offset(ox + ((p.x - minX) * scale).toFloat(), size.height - oy - ((p.y - minY) * scale).toFloat())
    fun toMap(o: Offset) = Vec2(minX + (o.x - ox) / scale, minY + (size.height - oy - o.y) / scale)
}

data class MapOverlay(
    val route: Route? = null,
    val user: Pose? = null,
    val lookAhead: Vec2? = null,
    val selectedPin: VisualPin? = null,
    val showDirectLine: Boolean = false,
    val highlightNodeId: String? = null,
    val showLabels: Boolean = true,
    val showLandmarks: Boolean = true,
)

@Composable
fun MapCanvas(
    venue: Venue,
    floor: Int,
    overlay: MapOverlay,
    modifier: Modifier = Modifier,
    onTap: ((Vec2) -> Unit)? = null,
    onDrag: ((Vec2) -> Unit)? = null,
) {
    val labelPaint = remember {
        Paint().apply { isAntiAlias = true; textSize = 26f; color = 0xFFCBD2DC.toInt(); typeface = Typeface.DEFAULT_BOLD }
    }
    val tapState = rememberUpdatedState(onTap)
    val dragState = rememberUpdatedState(onDrag)
    var m = Modifier.then(modifier)
    if (onTap != null) {
        m = m.pointerInput(venue.revision, floor) {
            detectTapGestures { o -> tapState.value?.invoke(MapProjection(venue, floor, Size(size.width.toFloat(), size.height.toFloat()), 28f).toMap(o)) }
        }
    }
    if (onDrag != null) {
        m = m.pointerInput(venue.revision, floor, "drag") {
            detectDragGestures { change, _ ->
                dragState.value?.invoke(MapProjection(venue, floor, Size(size.width.toFloat(), size.height.toFloat()), 28f).toMap(change.position))
            }
        }
    }
    Canvas(m) {
        val pr = MapProjection(venue, floor, size, 28f)
        val corridorW = (pr.scale * 2.2f).coerceIn(10f, 60f)
        // corridors (walkable edges)
        for (e in venue.edges) {
            val a = venue.node(e.a) ?: continue
            val b = venue.node(e.b) ?: continue
            if (a.floor != floor || b.floor != floor) continue
            drawLine(R360.Surface2, pr.toScreen(a.pos), pr.toScreen(b.pos), strokeWidth = corridorW, cap = StrokeCap.Round)
        }
        for (e in venue.edges) {
            val a = venue.node(e.a) ?: continue
            val b = venue.node(e.b) ?: continue
            if (a.floor != floor || b.floor != floor) continue
            drawLine(R360.Line, pr.toScreen(a.pos), pr.toScreen(b.pos), strokeWidth = 2f)
        }
        // direct (wrong) line to the pin, to show why route-aware guidance matters
        val user = overlay.user
        val pin = overlay.selectedPin
        if (overlay.showDirectLine && user != null && pin != null && user.floor == floor) {
            drawLine(
                R360.Bad.copy(alpha = 0.8f), pr.toScreen(user.pos), pr.toScreen(pin.pos), strokeWidth = 4f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))
            )
        }
        // active route
        overlay.route?.let { r ->
            val path = Path()
            var started = false
            for (p in r.points) {
                if (p.floor != floor) { started = false; continue }
                val s = pr.toScreen(p.pos)
                if (!started) { path.moveTo(s.x, s.y); started = true } else path.lineTo(s.x, s.y)
            }
            drawPath(path, R360.Go, style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // nodes
        for (n in venue.nodes) {
            if (n.floor != floor) continue
            val s = pr.toScreen(n.pos)
            val hl = n.id == overlay.highlightNodeId
            drawCircle(if (hl) R360.Warn else Color(0xFF5B6575), radius = if (hl) 9f else 4.5f, center = s)
        }
        // landmarks
        if (overlay.showLandmarks) for (l in venue.landmarks) {
            if (l.floor != floor) continue
            val s = pr.toScreen(l.pos)
            val col = if (l.located) R360.Info else R360.Warn
            drawRect(col, topLeft = Offset(s.x - 7f, s.y - 7f), size = Size(14f, 14f))
        }
        // pins
        for (p in venue.pins) {
            if (p.floor != floor) continue
            val s = pr.toScreen(p.pos)
            val sel = p.id == pin?.id
            drawCircle(if (sel) R360.Go else Color(0xFFB8C0CC), radius = if (sel) 13f else 6.5f, center = s)
            if (sel) drawCircle(R360.Bg, radius = 5f, center = s)
            if (overlay.showLabels && (sel || pr.scale > 30f)) drawLabel(p.name, s, labelPaint)
        }
        // look-ahead point
        overlay.lookAhead?.let { drawCircle(R360.Go.copy(alpha = 0.5f), radius = 10f, center = pr.toScreen(it), style = Stroke(4f)) }
        // user + heading cone
        if (user != null && user.floor == floor) {
            val s = pr.toScreen(user.pos)
            rotate(user.headingDeg.toFloat(), pivot = s) {
                val cone = Path().apply {
                    moveTo(s.x, s.y); lineTo(s.x - 26f, s.y - 62f); lineTo(s.x + 26f, s.y - 62f); close()
                }
                drawPath(cone, R360.Info.copy(alpha = 0.35f))
            }
            drawCircle(Color.White, radius = 12f, center = s)
            drawCircle(R360.Info, radius = 8f, center = s)
        }
    }
}

private fun DrawScope.drawLabel(text: String, at: Offset, paint: Paint) {
    val t = if (text.length > 18) text.take(17) + "…" else text
    drawContext.canvas.nativeCanvas.drawText(t, at.x + 14f, at.y - 10f, paint)
}
