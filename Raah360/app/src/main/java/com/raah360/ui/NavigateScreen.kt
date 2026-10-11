package com.raah360.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.raah360.AppViewModel
import com.raah360.ar.ArEngine
import com.raah360.ar.LandmarkImage
import com.raah360.core.NavPhase
import com.raah360.core.Venue
import com.raah360.core.VerifyResult
import com.raah360.data.VenueRepository
import com.raah360.nav.Navigator
import kotlin.math.roundToInt

fun landmarkImages(repo: VenueRepository, v: Venue, filter: (String) -> Boolean = { true }): List<LandmarkImage> =
    v.landmarks.filter { filter(it.id) }.mapNotNull { l ->
        repo.loadBitmap(v.id, l.image, 1024)?.let { LandmarkImage(l.id, it, l.widthM.toFloat()) }
    }

/** Hosts an ArEngine for the lifetime of the composable and follows the activity lifecycle. */
@Composable
fun rememberArEngine(onError: (String?) -> Unit): ArEngine {
    val ctx = LocalContext.current
    val engine = remember { ArEngine(ctx.findActivity()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> onError(engine.resume())
                Lifecycle.Event.ON_PAUSE -> engine.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            engine.pause()
            engine.destroy()
        }
    }
    return engine
}

@Composable
fun NavigateScreen(vm: AppViewModel, pinId: String) {
    CameraPermissionGate { NavigateContent(vm, pinId) }
}

@Composable
private fun NavigateContent(vm: AppViewModel, pinId: String) {
    val venue by vm.venue.collectAsState()
    val voiceOn by vm.voice.collectAsState()
    val debug by vm.debug.collectAsState()
    val pin = venue.pin(pinId) ?: run { Text("Place not found", color = R360.Text); return }
    var arError by remember { mutableStateOf<String?>(null) }
    val engine = rememberArEngine { arError = it }
    val navigator = remember(pinId) { Navigator(venue, pin, vm.speaker, vm.haptics) }
    val ui by navigator.ui.collectAsState()
    var showMap by remember { mutableStateOf(false) }
    val pinPhoto = remember(pin.image) { vm.repo.loadBitmap(venue.id, pin.image, 512) }

    LaunchedEffect(engine, navigator) {
        engine.setLandmarks(landmarkImages(vm.repo, venue))
        engine.onUpdate = { u ->
            navigator.onAr(u)
            engine.signatureEveryMs = if (navigator.wantsSignatures) 200L else 0L
        }
    }
    DisposableEffect(navigator) { onDispose { engine.onUpdate = null; vm.speaker.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { engine.surface }, modifier = Modifier.fillMaxSize())
        // dim the camera a little so the guidance stays readable
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
            // ---- top bar ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(pin.name, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(venue.floorName(ui.pose?.floor ?: pin.floor), color = R360.Muted, style = MaterialTheme.typography.labelMedium)
                }
                val (label, col) = when (ui.guidance.phase) {
                    NavPhase.NAVIGATING, NavPhase.ARRIVED -> "Tracking good" to R360.Go
                    NavPhase.UNSTABLE -> "Unstable" to R360.Warn
                    NavPhase.LOST -> "Lost" to R360.Bad
                    NavPhase.OFF_ROUTE -> "Off route" to R360.Bad
                    NavPhase.FLOOR_CHANGE -> "Floor change" to R360.Warn
                    NavPhase.LOCALIZING -> "Locating" to R360.Warn
                }
                Pill(label, col)
            }
            arError?.let {
                Surface(color = R360.Bad.copy(alpha = 0.9f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(it, color = Color.White, modifier = Modifier.padding(12.dp))
                }
            }
            ui.hint?.let { Pill(it, R360.Info, Modifier.padding(top = 8.dp)) }
            if (debug) {
                Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(ui.debug, color = Color(0xFF9EF0B5), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                }
            }

            Spacer(Modifier.weight(1f))

            if (showMap) {
                Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface.copy(alpha = 0.92f), modifier = Modifier.fillMaxWidth().height(200.dp).padding(bottom = 8.dp)) {
                    MapCanvas(
                        venue, ui.pose?.floor ?: pin.floor,
                        MapOverlay(route = ui.route, user = ui.pose, lookAhead = ui.guidance.lookAhead, selectedPin = pin, showLabels = false),
                        Modifier.fillMaxSize().padding(4.dp)
                    )
                }
            }

            when (ui.guidance.phase) {
                NavPhase.LOCALIZING, NavPhase.LOST -> LocalizePanel(vm, venue, ui.trackingText, ui.guidance.phase == NavPhase.LOST)
                NavPhase.ARRIVED -> VerifyPanel(
                    pinName = pin.name, photo = pinPhoto, hasSignature = pin.signature != null,
                    result = ui.verify?.result, similarity = ui.verify?.similarity,
                    message = ui.verify?.message ?: "Verifying…", detail = ui.verify?.detail ?: "",
                    onDone = { vm.home() }, onRetry = { navigator.restart() }
                )
                else -> GuidancePanel(
                    ui.guidance, "To ${pin.name}", voiceOn, { vm.setVoice(it) }, dialSize = 190.dp,
                    trackingText = if (ui.guidance.phase == NavPhase.NAVIGATING) null else ui.trackingText
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showMap = !showMap }) { Text(if (showMap) "Hide map" else "Show map", color = Color.White) }
                TextButton(onClick = { navigator.restart() }) { Text("Re-locate", color = Color.White) }
            }
        }
    }
}

@Composable
private fun LocalizePanel(vm: AppViewModel, venue: Venue, trackingText: String, lost: Boolean) {
    Surface(shape = RoundedCornerShape(24.dp), color = R360.Surface.copy(alpha = 0.92f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DirectionDial(null, size = 120.dp, searching = true)
            Spacer(Modifier.height(8.dp))
            Text(if (lost) "LOCATION LOST" else "SCAN A LANDMARK", style = MaterialTheme.typography.headlineMedium,
                color = if (lost) R360.Bad else R360.Warn, fontWeight = FontWeight.Black)
            Text(
                if (lost) "Scan a nearby mapped landmark to continue." else "Point the camera at any of these from 0.5–3 m away:",
                color = R360.Muted, style = MaterialTheme.typography.bodyMedium
            )
            Text(trackingText, color = R360.Muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(venue.landmarks, key = { it.id }) { l ->
                    val bmp = remember(l.image) { vm.repo.loadBitmap(venue.id, l.image, 256) }
                    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(bmp, l.name, 72.dp)
                        Text(l.name.substringAfter("· ").ifBlank { l.name }, color = R360.Text, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerifyPanel(
    pinName: String,
    photo: android.graphics.Bitmap?,
    hasSignature: Boolean,
    result: VerifyResult?,
    similarity: Double?,
    message: String,
    detail: String,
    onDone: () -> Unit,
    onRetry: () -> Unit,
) {
    val color = when (result) {
        VerifyResult.EXACT_ARRIVAL -> R360.Go
        VerifyResult.WRONG_SPOT -> R360.Bad
        else -> R360.Warn
    }
    Surface(shape = RoundedCornerShape(24.dp), color = R360.Surface.copy(alpha = 0.94f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (photo != null) {
                    Image(photo.asImageBitmap(), null, Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when (result) {
                            VerifyResult.EXACT_ARRIVAL -> "EXACT ARRIVAL"
                            VerifyResult.WRONG_SPOT -> "WRONG SPOT"
                            else -> "VERIFYING"
                        },
                        color = color, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black
                    )
                    Text(if (result == VerifyResult.EXACT_ARRIVAL) "You have reached $pinName" else message, color = R360.Text)
                    if (detail.isNotBlank()) Text(detail, color = R360.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (hasSignature && result != VerifyResult.EXACT_ARRIVAL) {
                Spacer(Modifier.height(10.dp))
                Text("Match with the destination photo", color = R360.Muted, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { (((similarity ?: 0.0) - 0.45) / 0.35).toFloat().coerceIn(0f, 1f) },
                    color = color, trackColor = R360.Surface2, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Text("${((similarity ?: 0.0) * 100).roundToInt()}%", color = R360.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRetry) { Text("Navigate again") }
                Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = R360.Go)) { Text("Done") }
            }
        }
    }
}
