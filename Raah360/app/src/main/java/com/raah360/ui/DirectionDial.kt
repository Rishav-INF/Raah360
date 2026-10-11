package com.raah360.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raah360.core.GuidanceState
import com.raah360.core.NavPhase
import com.raah360.core.Sector
import kotlin.math.roundToInt

/**
 * The circular 8-direction indicator. The highlighted green sector is the way to walk,
 * relative to the phone (top = straight ahead). It never rotates continuously.
 */
@Composable
fun DirectionDial(sector: Sector?, modifier: Modifier = Modifier, size: Dp = 220.dp, searching: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val phase by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "p"
    )
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        val ringW = r * 0.26f
        val arcR = r - ringW / 2 - 2f
        val tl = Offset(c.x - arcR, c.y - arcR)
        val arcSize = Size(arcR * 2, arcR * 2)
        // base ring
        for (s in Sector.entries) {
            val active = s == sector
            val start = -90f + s.centerDeg.toFloat() - 22.5f + 3f
            drawArc(
                color = if (active) R360.Go else Color.White.copy(alpha = 0.13f),
                startAngle = start, sweepAngle = 39f, useCenter = false,
                topLeft = tl, size = arcSize, style = Stroke(width = ringW, cap = StrokeCap.Butt)
            )
        }
        if (sector != null) {
            // glow + chevron in the active sector
            val start = -90f + sector.centerDeg.toFloat() - 22.5f + 3f
            drawArc(
                color = R360.Go.copy(alpha = 0.25f), startAngle = start - 2f, sweepAngle = 43f, useCenter = false,
                topLeft = Offset(c.x - arcR - ringW * 0.35f, c.y - arcR - ringW * 0.35f),
                size = Size((arcR + ringW * 0.35f) * 2, (arcR + ringW * 0.35f) * 2), style = Stroke(width = ringW * 0.3f)
            )
            rotate(sector.centerDeg.toFloat(), pivot = c) {
                val tipY = c.y - r * 0.50f
                val baseY = c.y - r * 0.30f
                val w = r * 0.17f
                val p = Path().apply {
                    moveTo(c.x, tipY); lineTo(c.x + w, baseY); lineTo(c.x, baseY - w * 0.45f); lineTo(c.x - w, baseY); close()
                }
                drawPath(p, R360.Go)
            }
        } else if (searching) {
            drawCircle(R360.Warn.copy(alpha = (1f - phase) * 0.6f), radius = r * (0.25f + 0.45f * phase), center = c, style = Stroke(width = 4f))
        }
        // the user in the centre
        drawCircle(Color.White, radius = r * 0.10f, center = c)
        drawCircle(R360.Bg, radius = r * 0.055f, center = c)
        // "front" tick
        drawLine(Color.White.copy(alpha = 0.5f), Offset(c.x, c.y - r * 0.16f), Offset(c.x, c.y - r * 0.22f), strokeWidth = 4f)
    }
}

/** Dial + big instruction + distance + "toward X" + voice switch. Shared by camera and simulator. */
@Composable
fun GuidancePanel(
    g: GuidanceState,
    destination: String,
    voiceOn: Boolean,
    onVoice: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 210.dp,
    trackingText: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = R360.Surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(destination, style = MaterialTheme.typography.labelLarge, color = R360.Muted, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            val showDial = g.phase == NavPhase.NAVIGATING || g.phase == NavPhase.UNSTABLE || g.phase == NavPhase.OFF_ROUTE ||
                g.phase == NavPhase.LOCALIZING || g.phase == NavPhase.LOST
            if (showDial) {
                DirectionDial(if (g.phase == NavPhase.LOST || g.phase == NavPhase.LOCALIZING) null else g.sector, size = dialSize,
                    searching = g.phase == NavPhase.LOCALIZING || g.phase == NavPhase.LOST)
                Spacer(Modifier.height(10.dp))
            }
            val headColor = when (g.phase) {
                NavPhase.LOST, NavPhase.OFF_ROUTE -> R360.Bad
                NavPhase.LOCALIZING, NavPhase.UNSTABLE, NavPhase.FLOOR_CHANGE -> R360.Warn
                else -> R360.Text
            }
            Text(g.headline, style = MaterialTheme.typography.headlineLarge, color = headColor, textAlign = TextAlign.Center)
            if (g.phase == NavPhase.NAVIGATING || g.phase == NavPhase.UNSTABLE) {
                g.nextActionM?.let {
                    Text(
                        "${if (it < 10) ((it * 2).roundToInt() / 2.0).toString().removeSuffix(".0") else it.roundToInt().toString()} m",
                        fontSize = 40.sp, fontWeight = FontWeight.Black, color = R360.Go
                    )
                }
            }
            if (g.nextAction.isNotBlank()) Text(g.nextAction, style = MaterialTheme.typography.bodyLarge, color = R360.Text, textAlign = TextAlign.Center)
            g.towardName?.let {
                if (g.phase == NavPhase.NAVIGATING || g.phase == NavPhase.UNSTABLE) Text("Toward $it", style = MaterialTheme.typography.bodyMedium, color = R360.Muted)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    trackingText ?: if (g.remainingM > 0) "${g.remainingM.roundToInt()} m to go" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (g.phase == NavPhase.NAVIGATING || g.phase == NavPhase.ARRIVED) R360.Muted else R360.Warn,
                    modifier = Modifier.weight(1f)
                )
                Text("Voice guidance", style = MaterialTheme.typography.labelMedium, color = R360.Muted)
                Spacer(Modifier.padding(4.dp))
                Switch(checked = voiceOn, onCheckedChange = onVoice)
            }
        }
    }
}
