package com.raah360.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raah360.AppViewModel
import com.raah360.core.Angles
import com.raah360.core.NavPhase
import com.raah360.core.Pose
import com.raah360.core.TrackingQuality
import com.raah360.core.Vec2
import com.raah360.core.VerifyResult
import com.raah360.nav.Navigator
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Desk-testable navigation: drag yourself on the plan (or press Walk) and get exactly the
 * guidance, sectors and voice the camera mode produces. No ARCore needed.
 */
@Composable
fun SimulatorScreen(vm: AppViewModel, pinId: String) {
    val venue by vm.venue.collectAsState()
    val voiceOn by vm.voice.collectAsState()
    var currentPinId by remember { mutableStateOf(pinId) }
    val pin = venue.pin(currentPinId) ?: venue.pins.firstOrNull() ?: run { Text("No places on this map"); return }
    val navigator = remember(pin.id, venue.revision) { Navigator(venue, pin, vm.speaker, vm.haptics) }
    val ui by navigator.ui.collectAsState()

    val startNode = remember(venue.revision) { venue.nodes.firstOrNull { it.floor == pin.floor } }
    var pos by remember(navigator) { mutableStateOf(startNode?.pos ?: Vec2.ZERO) }
    var heading by remember(navigator) { mutableDoubleStateOf(90.0) }
    var walking by remember(navigator) { mutableStateOf(false) }
    var noise by remember { mutableStateOf(true) }
    var lost by remember(navigator) { mutableStateOf(false) }
    var showDirect by remember { mutableStateOf(true) }
    var pinMenu by remember { mutableStateOf(false) }
    var startMenu by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.2f) }

    // 30 Hz simulation loop
    LaunchedEffect(navigator) {
        val rnd = Random(42)
        var last = System.currentTimeMillis()
        while (true) {
            delay(33)
            val now = System.currentTimeMillis()
            val dt = (now - last) / 1000.0
            last = now
            val g = navigator.ui.value.guidance
            if (walking && g.phase != NavPhase.ARRIVED && !lost) {
                // the simulated person follows the look-ahead point, like a real user following the dial
                val target = g.lookAhead
                if (target != null) {
                    val want = Angles.bearing(pos, target)
                    val turn = Angles.norm(want - heading).coerceIn(-180 * dt, 180 * dt)
                    heading = Angles.norm(heading + turn)
                    if (kotlin.math.abs(Angles.norm(want - heading)) < 60) pos = pos + Vec2.fromHeading(heading, speed * dt)
                }
            }
            val jitterPos = if (noise) Vec2(rnd.nextDouble(-0.12, 0.12), rnd.nextDouble(-0.12, 0.12)) else Vec2.ZERO
            val jitterH = if (noise) rnd.nextDouble(-7.0, 7.0) else 0.0
            val pose = Pose(pos + jitterPos, heading + jitterH, pin.floor)
            navigator.feed(if (lost) null else pose, if (lost) TrackingQuality.LOST else TrackingQuality.GOOD, now,
                trackingText = if (lost) "Location lost — scan a nearby mapped landmark" else "Simulated tracking")
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Simulator", "Drag the blue dot · same engine as camera mode", onBack = { vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface, modifier = Modifier.fillMaxWidth().height(300.dp)) {
                MapCanvas(
                    venue, pin.floor,
                    MapOverlay(route = ui.route, user = Pose(pos, heading, pin.floor), lookAhead = ui.guidance.lookAhead, selectedPin = pin, showDirectLine = showDirect),
                    Modifier.fillMaxSize().padding(4.dp),
                    onDrag = { p ->
                        val d = p - pos
                        if (d.length() > 0.05) heading = d.heading()
                        pos = p; walking = false
                    },
                    onTap = { p -> pos = p; walking = false },
                )
            }
            if (showDirect) Text("Red dashed line = what a naive arrow would point at (through the atrium). Green = the walkable route.",
                style = MaterialTheme.typography.labelMedium, color = R360.Muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { pinMenu = true }) { Text("To: ${pin.name.take(16)}") }
                    DropdownMenu(expanded = pinMenu, onDismissRequest = { pinMenu = false }) {
                        venue.pins.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { currentPinId = p.id; pinMenu = false }) }
                    }
                }
                Box {
                    OutlinedButton(onClick = { startMenu = true }) { Text("Start at…") }
                    DropdownMenu(expanded = startMenu, onDismissRequest = { startMenu = false }) {
                        venue.nodes.filter { it.floor == pin.floor && it.isNamed }.forEach { n ->
                            DropdownMenuItem(text = { Text(n.name) }, onClick = {
                                pos = n.pos; walking = false; startMenu = false; navigator.restart()
                            })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { walking = !walking }) { Text(if (walking) "Pause" else "Walk") }
                FilledTonalButton(onClick = { heading = Angles.norm(heading - 45) }) { Text("⟲ 45°") }
                FilledTonalButton(onClick = { heading = Angles.norm(heading + 45) }) { Text("45° ⟳") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = noise, onClick = { noise = !noise }, label = { Text("Sensor noise") })
                FilterChip(selected = lost, onClick = { lost = !lost }, label = { Text("Lose tracking") })
                FilterChip(selected = showDirect, onClick = { showDirect = !showDirect }, label = { Text("Naive arrow") })
            }
            Text("Walking speed ${"%.1f".format(speed)} m/s", style = MaterialTheme.typography.labelMedium, color = R360.Muted)
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.5f)
            Spacer(Modifier.height(4.dp))
            if (ui.guidance.phase == NavPhase.ARRIVED) {
                Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val v = ui.verify
                        Text(
                            when (v?.result) {
                                VerifyResult.EXACT_ARRIVAL -> "EXACT ARRIVAL"
                                VerifyResult.WRONG_SPOT -> "WRONG SPOT"
                                else -> "ARRIVED · verifying"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (v?.result == VerifyResult.WRONG_SPOT) R360.Bad else R360.Go
                        )
                        Text(
                            if (pin.signature != null) "This pin has a reference photo: real verification needs the camera mode. ${v?.detail ?: ""}"
                            else (v?.detail ?: ""),
                            color = R360.Muted
                        )
                        OutlinedButton(onClick = { navigator.restart(); startNode?.let { pos = it.pos } }) { Text("Restart") }
                    }
                }
            } else {
                GuidancePanel(ui.guidance, "To ${pin.name}", voiceOn, { vm.setVoice(it) }, dialSize = 170.dp,
                    trackingText = ui.trackingText)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
