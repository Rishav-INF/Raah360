package com.raah360.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.raah360.AppViewModel
import com.raah360.ar.CameraImages
import com.raah360.core.Landmark
import com.raah360.core.NodeType
import com.raah360.core.Vec2
import com.raah360.core.VenueEditor
import com.raah360.nav.MapperController
import com.google.ar.core.TrackingState

private data class DialogSpec(
    val title: String,
    val label: String,
    val initial: String = "",
    val second: String? = null,
    val secondInitial: String = "",
    val onOk: (String, String) -> Unit,
)

@Composable
fun MapperScreen(vm: AppViewModel) {
    CameraPermissionGate { MapperContent(vm) }
}

@SuppressLint("MissingPermission")
@Composable
private fun MapperContent(vm: AppViewModel) {
    val venue by vm.venue.collectAsState()
    val ctx = LocalContext.current
    var arError by remember { mutableStateOf<String?>(null) }
    val engine = rememberArEngine { arError = it }
    val ctl = remember { MapperController(venue) }
    val mui by ctl.ui.collectAsState()
    var chainTail by remember { mutableStateOf<String?>(null) }
    var autoPath by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var floor by remember { mutableStateOf(1) }

    LaunchedEffect(venue) { ctl.setVenue(venue) }
    // rebuild ARCore's image database when landmark photos change
    val lmKey = venue.landmarks.joinToString { it.id + it.image + it.widthM }
    LaunchedEffect(lmKey) { engine.setLandmarks(landmarkImages(vm.repo, venue)) }
    LaunchedEffect(engine) { engine.onUpdate = { ctl.onAr(it) } }
    DisposableEffect(Unit) { onDispose { engine.onUpdate = null } }

    val pose = mui.pose
    fun toast(s: String) = vm.toast(s)

    fun dropNode(name: String = "", type: NodeType = NodeType.WAYPOINT) {
        val p = ctl.ui.value.pose ?: return toast("Not aligned yet")
        val (v2, n) = VenueEditor.addNode(vm.venue.value, p.pos, p.floor, name, type, chainTail)
        vm.updateVenue(v2); chainTail = n.id
    }

    // auto path: drop a waypoint every 2 m while walking
    LaunchedEffect(pose, autoPath) {
        if (!autoPath || pose == null) return@LaunchedEffect
        val tail = chainTail?.let { vm.venue.value.node(it) }
        if (tail == null || tail.pos.distanceTo(pose.pos) >= 2.0) dropNode()
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { engine.surface }, modifier = Modifier.fillMaxSize())
            Column(Modifier.statusBarsPadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Text("Mapper", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Pill(
                        when {
                            mui.tracking != TrackingState.TRACKING -> "AR not tracking"
                            mui.aligned -> "Aligned" + (mui.lastFixName?.let { " · ${it.substringBefore(" ·")}" } ?: "")
                            else -> "Not aligned"
                        },
                        if (mui.aligned && mui.tracking == TrackingState.TRACKING) R360.Go else R360.Warn
                    )
                }
                arError?.let { Text(it, color = R360.Bad) }
                pose?.let {
                    Text("x %.2f  y %.2f  heading %.0f°  floor %d".format(it.pos.x, it.pos.y, it.headingDeg, it.floor),
                        color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                if (engine.landmarkDbErrors.isNotEmpty()) Text(engine.landmarkDbErrors.joinToString(), color = R360.Warn, style = MaterialTheme.typography.labelMedium)
                for (s in mui.seen) {
                    Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.name} · %.1f m %s".format(s.distanceM, if (s.located) "· measured" else "· estimate"),
                                color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
                            val mp = s.measuredPos
                            val my = s.measuredYaw
                            if (mui.aligned && mp != null && my != null) {
                                TextButton(onClick = {
                                    val lm = vm.venue.value.landmark(s.id) ?: return@TextButton
                                    if (s.distanceM > 3.5f) return@TextButton toast("Get closer (under 3.5 m) for an accurate measurement")
                                    vm.updateVenue(VenueEditor.upsertLandmark(vm.venue.value,
                                        lm.copy(pos = mp, yawDeg = my, located = true, floor = pose?.floor ?: lm.floor)))
                                    toast("${lm.name} measured")
                                }) { Text(if (s.located) "Re-measure" else "Save pose", color = R360.Go) }
                            }
                        }
                    }
                }
            }
        }

        Surface(color = R360.Surface, modifier = Modifier.fillMaxWidth().height(380.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp)) {
                if (!mui.aligned) {
                    Text("Align first", style = MaterialTheme.typography.titleMedium, color = R360.Text)
                    Text(
                        "Scan any Raah360 marker taped at its planned spot (see Landmarks), or stand in the corridor at the Room A corner " +
                            "facing along side A (towards Room A4) and tap “Set origin here”.",
                        color = R360.Muted, style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = {
                        val origin = venue.node("cAD")?.pos ?: Vec2.ZERO
                        if (ctl.setOriginHere(origin, 90.0, floor)) toast("Origin set") else toast("Wait for AR tracking")
                    }, modifier = Modifier.padding(top = 6.dp)) { Text("Set origin here") }
                }
                SectionLabel("Walkable graph")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { dropNode() }, enabled = mui.aligned) { Text("Drop node") }
                    FilterChip(selected = autoPath, onClick = { autoPath = !autoPath }, label = { Text("Auto path 2 m") }, enabled = mui.aligned)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(enabled = mui.aligned && chainTail != null, onClick = {
                        val p = pose ?: return@FilledTonalButton
                        val v = vm.venue.value
                        val target = v.nodes.filter { it.id != chainTail && it.floor == p.floor }.minByOrNull { it.pos.distanceTo(p.pos) }
                        if (target == null || target.pos.distanceTo(p.pos) > 3.0) return@FilledTonalButton toast("No node within 3 m")
                        vm.updateVenue(VenueEditor.connect(v, chainTail!!, target.id)); chainTail = null; autoPath = false
                        toast("Joined to ${target.name.ifBlank { target.id }}")
                    }) { Text("Join nearest") }
                    FilledTonalButton(enabled = mui.aligned, onClick = {
                        val p = pose ?: return@FilledTonalButton
                        val v = vm.venue.value
                        val n = v.nearestNode(p.pos, p.floor)
                        if (n == null || n.pos.distanceTo(p.pos) > 4.0) return@FilledTonalButton toast("No node within 4 m")
                        vm.updateVenue(VenueEditor.moveNode(v, n.id, p.pos))
                        toast("Moved ${n.name.ifBlank { n.id }} here (${"%.1f".format(n.pos.distanceTo(p.pos))} m)")
                    }) { Text("Move nearest here") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(enabled = chainTail != null, onClick = {
                        val id = chainTail ?: return@FilledTonalButton
                        val n = vm.venue.value.node(id) ?: return@FilledTonalButton
                        dialog = DialogSpec("Name this spot", "Name (e.g. Junction near lift)", n.name) { name, _ ->
                            vm.updateVenue(VenueEditor.renameNode(vm.venue.value, id, name.trim(), if (name.isBlank()) null else NodeType.JUNCTION))
                        }
                    }) { Text("Name node") }
                    FilledTonalButton(enabled = chainTail != null, onClick = {
                        val id = chainTail ?: return@FilledTonalButton
                        val v2 = VenueEditor.removeNode(vm.venue.value, id)
                        if (v2 === vm.venue.value) toast("A place uses this node; delete the place first") else { vm.updateVenue(v2); chainTail = null }
                    }) { Text("Delete node") }
                    FilledTonalButton(onClick = { if (!vm.undo()) toast("Nothing to undo") }) { Text("Undo") }
                }

                SectionLabel("Places & landmarks")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(enabled = mui.aligned, onClick = {
                        dialog = DialogSpec("New Visual Pin", "Name", "", "Category", "Room") { name, cat ->
                            if (name.isBlank()) return@DialogSpec
                            engine.captureFrame { bmp, sig ->
                                val p = ctl.ui.value.pose ?: return@captureFrame toast("Lost alignment, try again")
                                val (v2, pin) = VenueEditor.pinHere(vm.venue.value, name.trim(), p.pos, p.floor, p.headingDeg, cat.ifBlank { "Place" })
                                val file = bmp?.let { vm.repo.saveImage(v2.id, "pin_${pin.id}.jpg", it) }
                                vm.updateVenue(VenueEditor.upsertPin(v2, pin.copy(image = file, signature = sig)))
                                toast("Pinned ${pin.name} (photo saved)")
                            }
                        }
                    }) { Text("Pin here + photo") }
                    FilledTonalButton(enabled = mui.aligned, onClick = {
                        val p = pose ?: return@FilledTonalButton
                        val v = vm.venue.value
                        val pin = v.pins.filter { it.floor == p.floor }.minByOrNull { it.pos.distanceTo(p.pos) }
                        if (pin == null || pin.pos.distanceTo(p.pos) > 3.0) return@FilledTonalButton toast("Stand within 3 m of a place")
                        engine.captureFrame { bmp, sig ->
                            val file = bmp?.let { vm.repo.saveImage(v.id, "pin_${pin.id}.jpg", it) }
                            vm.updateVenue(VenueEditor.upsertPin(vm.venue.value, pin.copy(image = file, signature = sig, facingDeg = p.headingDeg)))
                            toast("Reference photo updated for ${pin.name}")
                        }
                    }) { Text("Photo for nearest place") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(enabled = mui.aligned, onClick = {
                        dialog = DialogSpec("New photo landmark", "Name (e.g. Gate 2 sign)", "", "Real width in metres", "0.40") { name, w ->
                            val width = w.toDoubleOrNull()?.coerceIn(0.05, 5.0) ?: 0.4
                            engine.captureFrame { bmp, _ ->
                                val p = ctl.ui.value.pose ?: return@captureFrame toast("Lost alignment, try again")
                                if (bmp == null) return@captureFrame toast("Camera frame not ready")
                                val v = vm.venue.value
                                val id = VenueEditor.newId("L", v.landmarks.map { it.id })
                                val file = vm.repo.saveImage(v.id, "lm_$id.jpg", CameraImages.centerCrop(bmp, 0.6f))
                                val lm = Landmark(id, name.ifBlank { id }, p.floor, p.pos + Vec2.fromHeading(p.headingDeg, 1.0),
                                    com.raah360.core.Angles.norm(p.headingDeg + 180), width, file, false,
                                    "Photo landmark added in Mapper")
                                vm.updateVenue(VenueEditor.upsertLandmark(v, lm))
                                toast("Saved. Keep pointing at it and tap “Save pose” when it is recognised")
                            }
                        }
                    }) { Text("Landmark from photo") }
                    FilledTonalButton(onClick = {
                        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val loc = runCatching {
                            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                        }.getOrNull()
                        if (loc == null) toast("No GPS fix (allow location in the place screen first)")
                        else { vm.updateVenue(vm.venue.value.copy(lat = loc.latitude, lng = loc.longitude)); toast("Venue GPS saved") }
                    }) { Text("Venue GPS here") }
                }

                SectionLabel("Map · tap a node to select it")
                Surface(shape = RoundedCornerShape(16.dp), color = R360.Bg, modifier = Modifier.fillMaxWidth().height(230.dp)) {
                    MapCanvas(
                        venue, pose?.floor ?: floor,
                        MapOverlay(user = pose, highlightNodeId = chainTail, showLabels = false),
                        Modifier.fillMaxSize().padding(4.dp),
                        onTap = { p ->
                            val n = vm.venue.value.nearestNode(p, pose?.floor ?: floor)
                            chainTail = if (n != null && n.pos.distanceTo(p) < 2.5) n.id else null
                        }
                    )
                }
                val unreachable = remember(venue.revision) { VenueEditor.unreachableNodes(venue).size }
                Text(
                    "${venue.nodes.size} nodes · ${venue.edges.size} edges · ${venue.pins.size} places · " +
                        "${venue.landmarks.count { it.located }}/${venue.landmarks.size} landmarks measured" +
                        if (unreachable > 0) " · ⚠ $unreachable unreachable nodes" else "",
                    color = if (unreachable > 0) R360.Warn else R360.Muted, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                chainTail?.let { id -> Text("Selected: ${venue.node(id)?.name?.ifBlank { id } ?: id}", color = R360.Warn, style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    dialog?.let { d ->
        var a by remember(d) { mutableStateOf(d.initial) }
        var b by remember(d) { mutableStateOf(d.secondInitial) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.title) },
            text = {
                Column {
                    OutlinedTextField(a, { a = it }, label = { Text(d.label) }, singleLine = true)
                    if (d.second != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(b, { b = it }, label = { Text(d.second) }, singleLine = true,
                            keyboardOptions = if (d.second.contains("metres")) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null; d.onOk(a, b) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}
