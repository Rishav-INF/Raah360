package com.raah360.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raah360.AppViewModel
import com.raah360.core.Geo
import com.raah360.core.GeoPoint
import com.raah360.core.Lang
import com.raah360.core.Venue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun distanceText(m: Double) = if (m >= 1000) "%.1f km".format(m / 1000) else "${(m / 10).roundToInt() * 10} m"

/**
 * Home: every 3D map on the phone, nearest first by GPS. Tap one to open that building.
 */
@Composable
fun VenuesScreen(vm: AppViewModel) {
    val lang = LocalLang.current
    val fix by vm.gps.fix.collectAsState()
    var venues by remember { mutableStateOf<List<Venue>?>(null) }
    var langDialog by remember { mutableStateOf(false) }

    var gpsAllowed by remember { mutableStateOf(vm.gps.hasPermission) }
    val askGps = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> gpsAllowed = r.values.any { it } }
    LaunchedEffect(Unit) {
        if (!gpsAllowed) askGps.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        venues = withContext(Dispatchers.IO) { vm.catalog() }
    }
    DisposableEffect(gpsAllowed) {
        if (gpsAllowed) vm.gps.start()
        onDispose { vm.gps.stop() }
    }

    // a place link copied from a chat: chat apps don't make raah360:// links tappable
    val ctx = LocalContext.current
    var sharedLink by remember { mutableStateOf<android.net.Uri?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                // the clipboard is readable once the window has focus
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    sharedLink = runCatching { vm.sharedLinkIn(cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)) }.getOrNull()
                }, 400)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val here = fix?.let { GeoPoint(it.latitude, it.longitude) }
    val sorted = remember(venues, here) {
        venues?.map { v ->
            val d = if (here != null && v.lat != null && v.lng != null) Geo.distance(here, GeoPoint(v.lat, v.lng)) else null
            v to d
        }?.sortedWith(compareBy<Pair<Venue, Double?>> { it.second == null }.thenBy { it.second ?: 0.0 }.thenBy { it.first.name })
    }

    Column(Modifier.fillMaxSize().background(R360.Bg).statusBarsPadding().navigationBarsPadding()) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Raah360", color = R360.Go, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { langDialog = true }) {
                Text(lang.nativeName.take(if (lang == Lang.EN) 2 else 3), color = R360.Info, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(tr("maps_near"), style = MaterialTheme.typography.headlineLarge, color = R360.Text)
            Text(tr("maps_sub"), style = MaterialTheme.typography.bodyLarge, color = R360.Muted)
            Spacer(Modifier.height(10.dp))
            when {
                !gpsAllowed -> Pill(tr("no_gps_sort"), R360.Warn, Modifier.clickable {
                    askGps.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                })
                fix == null -> Pill(tr("gps_finding"), R360.Muted)
                else -> Pill("GPS ±${fix!!.accuracy.roundToInt()} m · %.5f, %.5f".format(fix!!.latitude, fix!!.longitude), R360.Go)
            }
        }
        sharedLink?.let { link ->
            Surface(shape = RoundedCornerShape(16.dp), color = R360.GoDim, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("open_shared"), color = R360.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("", "")) }
                        sharedLink = null
                        vm.openDeepLink(link)
                    }) { Text(tr("open"), color = R360.Go, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val list = sorted
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = R360.Go) }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.first.id }) { (v, d) ->
                    VenueCard(v, d, nearest = list.firstOrNull()?.first?.id == v.id && d != null) { vm.openVenue(v.id) }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
    if (langDialog) LanguageDialog(lang, onPick = { vm.setLang(it); langDialog = false }, onDismiss = { langDialog = false })
}

@Composable
private fun VenueCard(v: Venue, distanceM: Double?, nearest: Boolean, onOpen: () -> Unit) {
    val here = distanceM != null && distanceM < 150
    val demo = v.id.startsWith("demo-")
    val floor = v.pins.firstOrNull()?.floor ?: v.nodes.firstOrNull()?.floor ?: 0
    Surface(
        shape = RoundedCornerShape(20.dp), color = R360.Surface,
        modifier = Modifier.fillMaxWidth()
            .then(if (nearest) Modifier.border(2.dp, R360.Go, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // top-down preview of the building's walkable layout
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(R360.Bg)) {
                MapCanvas(v, floor, MapOverlay(showLabels = false, showLandmarks = false), Modifier.fillMaxSize().padding(4.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name.removeSuffix(" (demo)"), style = MaterialTheme.typography.titleMedium, color = R360.Text, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(if (demo) "3D · " + tr("places_n", "n" to v.pins.size) else v.address.ifBlank { "3D" } + " · " + tr("places_n", "n" to v.pins.size),
                    style = MaterialTheme.typography.bodyMedium, color = R360.Muted, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (here) Pill(tr("you_are_here"), R360.Go)
                    if (demo) Pill(tr("demo"), R360.Warn)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (distanceM != null) {
                    Text(distanceText(distanceM), style = MaterialTheme.typography.titleLarge, color = if (here) R360.Go else R360.Text, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
