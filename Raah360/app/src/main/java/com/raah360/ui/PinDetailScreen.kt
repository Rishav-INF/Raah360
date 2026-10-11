package com.raah360.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.raah360.AppViewModel
import com.raah360.Screen
import com.raah360.ar.ArEngine
import com.raah360.core.RoutePlanner
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
private fun lastLocation(ctx: Context): Location? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

@Composable
fun PinDetailScreen(vm: AppViewModel, pinId: String) {
    val venue by vm.venue.collectAsState()
    val pin = venue.pin(pinId) ?: run { Text("Place not found", color = R360.Text); return }
    val ctx = LocalContext.current
    val photo = remember(pin.image, venue.revision) { vm.repo.loadBitmap(venue.id, pin.image, 1024) }
    val arSupported = remember { runCatching { ArEngine.isSupported(ctx) }.getOrDefault(false) }
    var location by remember { mutableStateOf(lastLocation(ctx)) }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { location = lastLocation(ctx) }
    // preview of the route from the first corner, just to show what's ahead
    val preview = remember(venue.revision, pinId) {
        venue.nodes.firstOrNull { it.floor == pin.floor }?.let { RoutePlanner(venue).planToPin(it.pos, it.floor, pin) }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(pin.name, "${venue.floorName(pin.floor)} · ${pin.category}", onBack = { vm.back() }) {
            IconButton(onClick = {
                val link = "raah360://pin/${venue.id}/${pin.id}"
                val text = "Meet me at ${pin.name} (${venue.name}, ${venue.floorName(pin.floor)}).\nOpen in Raah360 for exact indoor directions: $link"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Share Visual Pin"))
            }) { Icon(Icons.Default.Share, "Share", tint = R360.Text) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (photo != null) {
                Image(
                    photo.asImageBitmap(), "Destination photo",
                    Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(12.dp))
            }
            if (pin.description.isNotBlank()) Text(pin.description, color = R360.Text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (pin.signature != null) "Visual Pin: arrival is verified against the reference photo."
                else "No reference photo yet: arrival is verified by position. Add one in Mapper → Pin photo.",
                color = R360.Muted, style = MaterialTheme.typography.bodyMedium
            )
            SectionLabel("Walkable route")
            Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface, modifier = Modifier.fillMaxWidth().height(240.dp)) {
                MapCanvas(venue, pin.floor, MapOverlay(route = preview, selectedPin = pin), Modifier.fillMaxSize().padding(6.dp))
            }

            SectionLabel("1 · Get to the building (GPS)")
            Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    val lat = venue.lat; val lng = venue.lng
                    if (lat == null || lng == null) {
                        Text("This venue has no GPS position yet (set it in Mapper). If you're inside, skip to step 2.", color = R360.Muted)
                    } else {
                        val loc = location
                        if (loc == null) {
                            Text("Location unknown.", color = R360.Muted)
                            TextButton(onClick = { locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Use my location") }
                        } else {
                            val res = FloatArray(1)
                            Location.distanceBetween(loc.latitude, loc.longitude, lat, lng, res)
                            val d = res[0]
                            Text(
                                if (d < 150) "You're at the building (${d.roundToInt()} m, GPS ±${loc.accuracy.roundToInt()} m). Go inside and continue."
                                else "Building is ${if (d > 1000) "%.1f km".format(d / 1000) else "${d.roundToInt()} m"} away.",
                                color = R360.Text
                            )
                        }
                        OutlinedButton(onClick = {
                            val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=walking")
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }) { Text("Directions to the building") }
                    }
                }
            }
            SectionLabel("2 · Inside: landmark → route → guide → verify")
            Text(
                "Point your camera at any mapped landmark (Raah360 markers or registered signs) to locate yourself. " +
                    "Guidance follows the corridors and never points through walls.",
                color = R360.Muted, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
        }
        Surface(color = R360.Surface) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.go(Screen.Simulator(pin.id)) }, modifier = Modifier.weight(1f)) { Text("Simulate") }
                Button(onClick = { vm.go(Screen.Navigate(pin.id)) }, enabled = arSupported, modifier = Modifier.weight(1.4f)) {
                    Text(if (arSupported) "Start navigation" else "AR not supported")
                }
            }
        }
    }
}
