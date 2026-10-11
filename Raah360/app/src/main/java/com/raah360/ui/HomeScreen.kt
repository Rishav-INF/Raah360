package com.raah360.ui

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.raah360.AppViewModel
import com.raah360.Screen
import com.raah360.StartPoint
import com.google.ar.core.TrackingState
import com.raah360.ar.ArEngine
import com.raah360.ar.ArOverlay
import com.raah360.ar.ArScene
import com.raah360.ar.ArUpdate
import com.raah360.core.ArAligner
import com.raah360.core.Angles
import com.raah360.core.FloorEstimator
import com.raah360.core.GeoLocator
import com.raah360.core.GeoPoint
import com.raah360.core.GuidanceEngine
import com.raah360.core.GuidanceState
import com.raah360.core.HeadingCalibrator
import com.raah360.core.I18n
import com.raah360.core.Lang
import com.raah360.core.ManeuverKind
import com.raah360.core.NavAnnouncer
import com.raah360.core.NavPhase
import com.raah360.core.NavPhrases
import com.raah360.core.Pose
import com.raah360.core.Route
import com.raah360.core.RoutePlanner
import com.raah360.core.StepWalker
import com.raah360.core.TrackingQuality
import com.raah360.core.Vec2
import com.raah360.core.Venue
import com.raah360.core.VisualPin
import com.raah360.core.WrongWayMonitor
import com.raah360.nav.Barometer
import com.raah360.nav.Compass
import com.raah360.nav.StepTracker
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private enum class Field { FROM, TO }

/** Demo modes for showing the app at a desk: walk the route, or walk the wrong way on purpose. */
private enum class Demo { WALK, WRONG }

private const val WALK_SPEED = 1.2
/** Seconds between simulated steps in demo mode (~1.7 steps/s, a normal walk). */
private const val DEMO_STEP_S = 0.6
/** AR: chevron spacing, how far ahead to draw, and how high a hand-held phone is above the floor. */
private const val CHEVRON_GAP_M = 1.2
private const val AR_AHEAD_M = 15.0
private const val CAMERA_HEIGHT_M = 1.35

/** Walking direction at [s] metres along the route, looking a metre ahead so corners turn smoothly. */
private fun Route.headingAt(s: Double): Double {
    val a = pointAt(s)
    val b = pointAt(s + 1.0)
    return if (b.distanceTo(a) > 0.05) (b - a).heading() else (a - pointAt(s - 1.0)).heading()
}

/** First thing to do at the start: which way to turn, using the place you stand at. */
private fun startInstruction(lang: Lang, venue: Venue, start: StartPoint, route: Route): String {
    val pin = start.pinId?.let(venue::pin)
    val facing = pin?.facingDeg ?: return I18n.t(lang, "start_generic")
    val rel = Angles.norm(route.headingAt(0.0) - facing)
    val key = when {
        abs(rel) < 45 -> "start_face"
        abs(rel) > 135 -> "start_back"
        rel > 0 -> "start_right"
        else -> "start_left"
    }
    return I18n.t(lang, key, "p" to pin.name)
}

private fun walkTime(lang: Lang, m: Double): String {
    val s = m / WALK_SPEED
    return if (s < 60) I18n.t(lang, "under_min") else I18n.t(lang, "about_min", "n" to (s / 60).roundToInt())
}

/**
 * The whole app on one screen: a 3D map of the building, "From" and "To", and Start.
 * GPS proposes where you are; picking a start flies the map there; Start follows you with your steps.
 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val venue by vm.venue.collectAsState()
    val from by vm.tripFrom.collectAsState()
    val toId by vm.tripTo.collectAsState()
    val pending by vm.pendingDestination.collectAsState()
    val fix by vm.gps.fix.collectAsState()
    val lang = LocalLang.current

    val map = remember { Map3DController() }
    var picker by remember { mutableStateOf<Field?>(null) }
    var navigating by rememberSaveable { mutableStateOf(false) }
    var freeCamera by remember { mutableStateOf(false) }
    var navTap by remember { mutableStateOf<Vec2?>(null) }
    var arActive by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(if (from != null) "start" else "overview") }
    var autoStartTried by rememberSaveable { mutableStateOf(false) }

    // ---- GPS: on while this screen is visible ----
    var gpsAllowed by remember { mutableStateOf(vm.gps.hasPermission) }
    val askGps = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        gpsAllowed = r.values.any { it }
    }
    LaunchedEffect(Unit) {
        if (!gpsAllowed) askGps.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    DisposableEffect(gpsAllowed) {
        if (gpsAllowed) vm.gps.start()
        onDispose { vm.gps.stop() }
    }

    val to = toId?.let(venue::pin)
    // storeys that have places; the switch shows when there is more than one
    val floors = remember(venue) { venue.pins.map { it.floor }.distinct().sorted() }
    var viewFloor by remember(venue.id) { mutableIntStateOf(from?.floor ?: if (1 in floors) 1 else floors.firstOrNull() ?: 0) }
    LaunchedEffect(from) { from?.let { viewFloor = it.floor } }
    val floor = viewFloor
    val pins = remember(venue, floor) { venue.pins.filter { it.floor == floor } }
    val multiFloor = floors.size > 1
    /** "Room F · Ground floor" where two storeys could be confused. */
    fun label(name: String, level: Int) = if (multiFloor) "$name · ${NavPhrases.floorName(lang, level)}" else name
    val geo = remember(venue) { GeoLocator(venue) }
    val here = fix?.let { GeoPoint(it.latitude, it.longitude) }
    val suggestions = remember(here, geo) { here?.let { geo.suggest(it) } ?: emptyList() }
    val route = remember(from, toId, venue.revision) {
        val f = from
        if (f == null || to == null || f.pinId == to.id) null else RoutePlanner(venue).planToPin(f.pos, f.floor, to)
    }

    fun startAt(p: VisualPin) { vm.tripFrom.value = StartPoint(p.name, p.pos, p.floor, p.id); focus = "start" }
    fun goTo(p: VisualPin) { vm.tripTo.value = p.id; focus = if (from != null) "route" else "overview" }

    // GPS picks the start by itself when it can clearly tell which place you're at
    LaunchedEffect(here, geo) {
        val h = here ?: return@LaunchedEffect
        if (autoStartTried || from != null) return@LaunchedEffect
        val s = geo.confidentStart(h, fix!!.accuracy.toDouble()) ?: return@LaunchedEffect
        autoStartTried = true
        startAt(s.pin)
        vm.toast(I18n.t(lang, "gps_auto", "p" to s.pin.name))
    }
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        venue.pin(id)?.let(::goTo)
        vm.consumePendingDestination()
    }

    // ---- after picking a start: your real arrow at that spot, turning with the phone ----
    val ctx = LocalContext.current
    val planCompass = remember { Compass(ctx) }
    val northDeg = venue.northDeg ?: HeadingCalibrator.fromGeo(geo.transform)
    var liveHeading by remember { mutableStateOf<Double?>(null) }
    val planning = from != null && !navigating
    DisposableEffect(planning) {
        if (planning) planCompass.start()
        onDispose { planCompass.stop() }
    }
    LaunchedEffect(from, navigating, northDeg) {
        val f = from
        if (f == null || navigating) {
            liveHeading = null
            if (!navigating) map.setUser(null)
            return@LaunchedEffect
        }
        val at = venue.snapToGraph(f.pos, f.floor)?.takeIf { it.distance < 3.0 }?.point ?: f.pos
        while (true) {
            val c = planCompass.headingDeg
            val h = if (c != null && northDeg != null) Angles.norm(c - northDeg) else null
            liveHeading = h
            if (h != null) map.setUser(at, h, f.floor)
            delay(80)
        }
    }

    // ---- keep the 3D view in sync ----
    LaunchedEffect(venue.id) { map.setVenue(venue, floors.firstOrNull() ?: 0) }
    LaunchedEffect(venue.pins) { map.setPlaces(venue.pins) }
    LaunchedEffect(venue.id, viewFloor, navigating) { if (!navigating) map.setFloor(viewFloor) }
    LaunchedEffect(from, toId, route) {
        map.setStart(from?.pos, from?.floor ?: floor)
        map.setDest(toId)
        map.setRoute(route)
        // before there's a route, look at the start the way you'd face its door, from the corridor
        val facing = from?.pinId?.let(venue::pin)?.facingDeg
        if (!navigating) map.view(if (from == null && focus == "start") "overview" else focus, facing)
    }

    fun onMapTap(p: Vec2) {
        if (navigating) { navTap = p; return }
        val near = pins.minByOrNull { it.pos.distanceTo(p) }?.takeIf { it.pos.distanceTo(p) < 3.5 }
        when (picker ?: if (from == null) Field.FROM else Field.TO) {
            Field.FROM -> {
                if (near != null) startAt(near) else {
                    val snap = venue.snapToGraph(p, floor)?.takeIf { it.distance < 4.0 }
                    if (snap == null) { vm.toast(I18n.t(lang, "tap_corridor")); return }
                    vm.tripFrom.value = StartPoint(I18n.t(lang, "your_spot"), snap.point, floor); focus = "start"
                }
            }
            Field.TO -> if (near != null) goTo(near) else vm.toast(I18n.t(lang, "tap_dest"))
        }
        picker = null
    }

    fun endNavigation() {
        navigating = false; freeCamera = false; arActive = false
        map.setUser(null); map.setTrail(null); map.setWrong(false); map.setRoute(route)
        map.setFloor(viewFloor)
        focus = "start"; map.view("start")
    }

    BackHandler(enabled = picker != null || navigating) {
        if (picker != null) picker = null else endNavigation()
    }

    Box(Modifier.fillMaxSize().background(R360.Bg)) {
        Map3D(map, Modifier.fillMaxSize(), onTap = ::onMapTap, onGesture = { freeCamera = true }, visible = !arActive)

        if (navigating && route != null && to != null && from != null) {
            NavigationLayer(
                vm, venue, map, route, to, from!!, navTap, freeCamera, arActive, { arActive = it },
                onTapUsed = { navTap = null },
                onRecenter = { freeCamera = false; map.view("follow") },
                onEnd = ::endNavigation,
            )
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                TripCard(vm, from?.let { label(it.name, it.floor) }, to?.let { label(it.name, it.floor) }, from != null || to != null,
                    picker, onPick = { picker = if (picker == it) null else it },
                    shareable = to ?: from?.pinId?.let(venue::pin)) {
                    vm.tripFrom.value = null; vm.tripTo.value = null; picker = null; focus = "overview"; map.view("overview")
                }
                Spacer(Modifier.height(8.dp))
                GpsLine(vm, gpsAllowed, fix, geo, suggestions, venue,
                    onAllow = { askGps.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    onUse = ::startAt)
                if (multiFloor) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        floors.reversed().forEach { f ->
                            FilterChip(viewFloor == f, { viewFloor = f }, label = { Text(NavPhrases.floorName(lang, f)) })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (freeCamera) TextButton(onClick = { freeCamera = false; map.view(if (route != null) "start" else "overview") }) {
                    Text(tr("reset_view"), color = R360.Text)
                }
                when {
                    picker != null -> PlacePicker(vm, venue.pins, picker!!, if (picker == Field.FROM) suggestions else emptyList(),
                        floorOf = { if (multiFloor) NavPhrases.floorName(lang, it.floor) else null },
                        onPick = { p -> if (picker == Field.FROM) startAt(p) else goTo(p); picker = null },
                        onClose = { picker = null })
                    from == null -> Hint(tr("where_are_you"), tr("hint_from")) { picker = Field.FROM }
                    to == null -> Hint(tr("where_to"), tr("hint_to")) { picker = Field.TO }
                    route == null -> Hint(tr("already_there"), tr("already_there_body")) { picker = Field.TO }
                    else -> RouteCard(venue, from!!, to, route, liveHeading) {
                        val near = here?.let { geo.distanceToBuilding(it) }?.let { it < 250 } ?: true
                        if (from!!.pinId != null && near) vm.learnGeo(from!!.pinId!!)
                        freeCamera = false
                        navigating = true
                    }
                }
            }
        }
    }
}

@Composable
private fun TripCard(
    vm: AppViewModel, fromLabel: String?, toLabel: String?, clearable: Boolean, active: Field?, onPick: (Field) -> Unit,
    shareable: VisualPin? = null, onClear: () -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var langDialog by remember { mutableStateOf(false) }
    val lang = LocalLang.current
    Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface.copy(alpha = 0.95f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TripRow(R360.Info, tr("from"), fromLabel, tr("where_are_you"), active == Field.FROM) { onPick(Field.FROM) }
                Box(Modifier.padding(start = 26.dp).fillMaxWidth().height(1.dp).background(R360.Line))
                TripRow(R360.Go, tr("to"), toLabel, tr("where_to"), active == Field.TO) { onPick(Field.TO) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = R360.Text) }
                    MoreMenu(vm, vm.tripTo.collectAsState().value?.let { vm.venue.value.pin(it) }, menu, onLanguage = { langDialog = true }) { menu = false }
                }
                // the language is one tap away, in its own script: "अ" / "ಅ" / "A"
                TextButton(onClick = { langDialog = true }) {
                    Text(lang.nativeName.take(if (lang == Lang.EN) 2 else 3), color = R360.Info, fontWeight = FontWeight.Bold)
                }
                // send the destination (or where you are) to a friend
                shareable?.let { pin ->
                    IconButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.shareText(pin))
                        }, I18n.t(lang, "share")))
                    }) { Icon(Icons.Default.Share, tr("share"), tint = R360.Go) }
                }
                if (clearable) IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear", tint = R360.Muted) }
            }
        }
    }
    if (langDialog) LanguageDialog(lang, onPick = { vm.setLang(it); langDialog = false }, onDismiss = { langDialog = false })
}

@Composable
private fun TripRow(color: Color, label: String, value: String?, placeholder: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .background(if (active) R360.Surface2 else Color.Transparent).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(label, color = R360.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp), maxLines = 1)
        Text(value ?: placeholder, color = if (value != null) R360.Text else R360.Muted,
            style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
private fun GpsLine(
    vm: AppViewModel, allowed: Boolean, fix: Location?, geo: GeoLocator, suggestions: List<GeoLocator.Suggestion>, venue: Venue,
    onAllow: () -> Unit, onUse: (VisualPin) -> Unit,
) {
    val ctx = LocalContext.current
    val here = fix?.let { GeoPoint(it.latitude, it.longitude) }
    val far = here?.let { geo.distanceToBuilding(it) }?.takeIf { it > 300 }
    val best = suggestions.firstOrNull()?.takeIf { it.distanceM < 15 }
    val acc = fix?.accuracy?.roundToInt() ?: 0
    val (text, color, action) = when {
        !allowed -> Triple(tr("gps_off"), R360.Warn, onAllow)
        !vm.gps.locationOn -> Triple(tr("gps_turn_on"), R360.Warn, {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        })
        fix == null -> Triple(tr("gps_finding"), R360.Muted, {})
        far != null -> Triple(tr("gps_far", "d" to (if (far > 1000) "%.1f km".format(far / 1000) else "${far.roundToInt()} m")), R360.Info, {
            val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${venue.lat},${venue.lng}&travelmode=walking")
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
        })
        best != null -> Triple(tr("gps_near", "a" to acc, "p" to best.pin.name), R360.Go, { onUse(best.pin) })
        geo.knownPlaces == 0 -> Triple(tr("gps_learn", "a" to acc), R360.Muted, {})
        else -> Triple(tr("gps_rough", "a" to acc), R360.Muted, {})
    }
    Pill(text, color, Modifier.clickable(onClick = action))
}

@Composable
private fun Hint(title: String, body: String, onSearch: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface.copy(alpha = 0.95f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = R360.Text)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = R360.Muted)
            }
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search", tint = R360.Text) }
        }
    }
}

@Composable
private fun RouteCard(venue: Venue, from: StartPoint, to: VisualPin, route: Route, liveHeading: Double?, onStart: () -> Unit) {
    val lang = LocalLang.current
    Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface.copy(alpha = 0.95f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${NavPhrases.metres(lang, route.length)} · ${walkTime(lang, route.length)}", style = MaterialTheme.typography.headlineMedium, color = R360.Text)
            Text("${from.name} → ${to.name}", style = MaterialTheme.typography.bodyMedium, color = R360.Muted, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(startInstruction(lang, venue, from, route), style = MaterialTheme.typography.bodyLarge, color = R360.Text)
            // live: compare the way you face (compass) with the way the route leaves
            liveHeading?.let { h ->
                val d = Angles.norm(route.headingAt(0.0) - h)
                val (key, color) = when {
                    abs(d) < 30 -> "face_ok" to R360.Go
                    abs(d) > 150 -> "face_around" to R360.Warn
                    d > 0 -> "face_right" to R360.Warn
                    else -> "face_left" to R360.Warn
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurnArrow(if (abs(d) < 30) 0.0 else d, 36.dp, color)
                    Spacer(Modifier.width(10.dp))
                    Text(tr(key), style = MaterialTheme.typography.bodyMedium, color = color)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = R360.Go),
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(tr("start"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PlacePicker(
    vm: AppViewModel, pins: List<VisualPin>, field: Field, suggestions: List<GeoLocator.Suggestion>,
    floorOf: (VisualPin) -> String?, onPick: (VisualPin) -> Unit, onClose: () -> Unit,
) {
    val lang = LocalLang.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val shown = pins.filter {
        (category == null || it.category == category) &&
            (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true))
    }
    Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface.copy(alpha = 0.97f), modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (field == Field.FROM) tr("where_are_you") else tr("where_to"), style = MaterialTheme.typography.titleLarge,
                    color = R360.Text, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = R360.Muted) }
            }
            Text(tr("or_tap_map"), style = MaterialTheme.typography.labelMedium, color = R360.Muted)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(tr("search")) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Shop", "Food", "Room", "Entrance", "Exit", "Facility", "Stairs", "Seating").filter { c -> pins.any { it.category == c } }.forEach { c ->
                    FilterChip(category == c, { category = if (category == c) null else c }, label = { Text(I18n.category(lang, c)) })
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (suggestions.isNotEmpty() && query.isBlank()) {
                    item { SectionLabel(tr("near_you")) }
                    items(suggestions, key = { "gps-" + it.pin.id }) { s ->
                        PinRow(vm, s.pin, trailing = "~${s.distanceM.roundToInt()} m", floor = floorOf(s.pin)) { onPick(s.pin) }
                    }
                    item { SectionLabel(tr("all_places")) }
                }
                items(shown, key = { it.id }) { p -> PinRow(vm, p, floor = floorOf(p)) { onPick(p) } }
            }
        }
    }
}

@Composable
private fun MoreMenu(vm: AppViewModel, to: VisualPin?, expanded: Boolean, onLanguage: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val debug by vm.debug.collectAsState()
    val arSupported = remember { runCatching { ArEngine.isSupported(ctx) }.getOrDefault(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importVenue(uri) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(tr("language") + " / Language") }, onClick = { onDismiss(); onLanguage() })
        if (to != null) {
            DropdownMenuItem(text = { Text("About ${to.name} / share") }, onClick = { onDismiss(); vm.go(Screen.PinDetail(to.id)) })
            if (arSupported) DropdownMenuItem(text = { Text("Camera (AR) guidance to ${to.name}") }, onClick = { onDismiss(); vm.go(Screen.Navigate(to.id)) })
            DropdownMenuItem(text = { Text("Simulator") }, onClick = { onDismiss(); vm.go(Screen.Simulator(to.id)) })
        }
        DropdownMenuItem(text = { Text("Mapper (edit the map)") }, onClick = { onDismiss(); vm.go(Screen.Mapper) })
        DropdownMenuItem(text = { Text("Landmarks") }, onClick = { onDismiss(); vm.go(Screen.Markers) })
        DropdownMenuItem(text = { Text("Share this map") }, onClick = {
            onDismiss()
            vm.exportVenue { f ->
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "Share venue map"))
            }
        })
        DropdownMenuItem(text = { Text("Import a map") }, onClick = {
            onDismiss(); importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        })
        DropdownMenuItem(text = { Text(if (debug) "Hide developer info" else "Show developer info") }, onClick = { onDismiss(); vm.setDebug(!debug) })
        DropdownMenuItem(text = { Text("Reset map to original survey") }, onClick = { onDismiss(); vm.resetToSurvey() })
    }
}

/**
 * Turn-by-turn on the 3D map. Each step moves you the way the phone faces (compass), kept on the
 * corridors. Walk away from the route and your path turns red, the phone buzzes and says so; keep
 * going and it plans a new route. The barometer watches the floor. A tap on the map corrects you.
 */
@Composable
private fun NavigationLayer(
    vm: AppViewModel, venue: Venue, map: Map3DController, startRoute: Route, dest: VisualPin, start: StartPoint,
    tap: Vec2?, freeCamera: Boolean, arMode: Boolean, onArMode: (Boolean) -> Unit,
    onTapUsed: () -> Unit, onRecenter: () -> Unit, onEnd: () -> Unit,
) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val voiceOn by vm.voice.collectAsState()
    val debug by vm.debug.collectAsState()
    var curFloor by remember(startRoute) { mutableIntStateOf(start.floor) }

    var route by remember(startRoute) { mutableStateOf(startRoute) }
    val engine = remember(startRoute) { GuidanceEngine(startRoute) }
    val monitor = remember(startRoute) { WrongWayMonitor(startRoute) }
    val walker = remember(startRoute) { StepWalker(venue, curFloor, startRoute.pointAt(0.0)) }
    val announcer = remember(startRoute, lang) { NavAnnouncer(lang, dest.name) }
    val compass = remember { Compass(ctx) }
    val calibrator = remember(startRoute) { HeadingCalibrator(venue.northDeg ?: HeadingCalibrator.fromGeo(GeoLocator(venue).transform)) }
    var calibrating by remember(startRoute) { mutableStateOf(compass.available && !calibrator.calibrated) }
    var demo by remember(startRoute) { mutableStateOf<Demo?>(null) }
    var g by remember(startRoute) { mutableStateOf(GuidanceState(NavPhase.NAVIGATING)) }
    var wrong by remember(startRoute) { mutableStateOf<WrongWayMonitor.State?>(null) }
    var shown by remember(startRoute) { mutableStateOf(walker.pos) }
    var steps by remember(startRoute) { mutableIntStateOf(0) }
    val estimator = remember(startRoute) { FloorEstimator(curFloor, venue.floorHeightM) }
    var detectedFloor by remember(startRoute) { mutableStateOf<Int?>(null) }
    val arrived = g.phase == NavPhase.ARRIVED
    // read live: the loop and the step tracker are started once, so a plain parameter would stay frozen
    val arOn by rememberUpdatedState(arMode)
    // ---- AR camera: ARCore tracks you; lined up with the map from your start point + compass ----
    val arSupported = remember { runCatching { ArEngine.isSupported(ctx) }.getOrDefault(false) }
    val aligner = remember(startRoute) { ArAligner(venue) }
    val overlay = remember { ArOverlay() }
    var arTracking by remember(startRoute) { mutableStateOf(false) }
    var arPose by remember(startRoute) { mutableStateOf<Pose?>(null) }
    var arLostSince by remember(startRoute) { mutableStateOf(0L) }
    var arEverTracked by remember(startRoute) { mutableStateOf(false) }
    /** AR: where the route goes relative to the way the phone points (degrees, + = right). */
    var arRouteRel by remember(startRoute) { mutableStateOf<Double?>(null) }
    var shownHeading by remember(startRoute) { mutableDoubleStateOf(startRoute.headingAt(0.0)) }
    LaunchedEffect(arMode) { if (!arMode) { overlay.scene = null; aligner.reset(); arTracking = false; arPose = null } }

    fun say(text: String) { if (voiceOn) vm.speaker.say(text, urgent = true) }

    /** Which way the visitor is walking, on the map. */
    fun walkHeading(): Double {
        val pr = route.project(walker.pos, curFloor)
        val along = route.headingAt(pr?.s ?: 0.0)
        demo?.let { return if (it == Demo.WRONG) Angles.norm(along + 180) else along }
        if (arOn && arTracking) arPose?.let { return it.headingDeg }
        compass.headingDeg?.let { c -> calibrator.toMap(c)?.let { return it } }
        return along // no compass, or calibration skipped: follow the route (wrong turns can't be seen)
    }

    fun applyWrongWay(st: WrongWayMonitor.State) {
        wrong = st
        map.setTrail(if (st.wrong) st.trail else null, curFloor)
        map.setWrong(st.wrong)
        if (st.justWentWrong) vm.haptics.warning()
        if (st.justRecovered) vm.haptics.success()
        if (st.shouldReroute) {
            RoutePlanner(venue).planToPin(walker.pos, curFloor, dest)?.let { r ->
                route = r; engine.reset(r); monitor.reset(r)
                map.setRoute(r); map.setTrail(null); map.setWrong(false)
                wrong = null
                say(I18n.t(lang, "recalculating"))
            }
        }
    }

    /** The floor change waiting at the stairs / escalator / lift just ahead, if any. */
    val pendingFloor = g.nextManeuver?.takeIf { g.phase == NavPhase.FLOOR_CHANGE && it.kind == ManeuverKind.FLOOR_CHANGE }?.toFloor

    /** You took the stairs / escalator: continue on [to], from where that link arrives. */
    fun switchFloor(to: Int) {
        val m = g.nextManeuver?.takeIf { it.kind == ManeuverKind.FLOOR_CHANGE }
        curFloor = to
        walker.floor = to
        m?.let { route.points.getOrNull(it.pointIndex + 1)?.pos }?.let(walker::place)
        shown = walker.pos
        estimator.rebase(to)
        map.setFloor(to)
        vm.haptics.success()
    }

    fun onStep() {
        if (arrived || calibrating || g.phase == NavPhase.FLOOR_CHANGE) return
        val h = walkHeading()
        val pos = walker.step(h)
        steps++
        val st = monitor.update(pos, curFloor, h)
        // keep the compass honest while clearly walking along the route
        if (demo == null && !st.wrong) compass.headingDeg?.let { c ->
            route.project(pos, curFloor)?.takeIf { it.lateral < 1.0 }?.let { calibrator.refine(c, route.headingAt(it.s)) }
        }
        applyWrongWay(st)
    }

    val stepRef = rememberUpdatedState { onStep() }
    // while AR is tracking it moves you; steps only fill in when it isn't
    val tracker = remember { StepTracker(ctx) { if (demo == null && !(arOn && arTracking)) stepRef.value() } }
    val barometer = remember { Barometer(ctx) { p -> detectedFloor = estimator.feed(p) } }
    fun compassMap(): Double? = compass.headingDeg?.let { calibrator.toMap(it) }

    /** One ARCore frame (posted to the main thread). */
    fun onAr(u: ArUpdate) {
        val cam = u.camPos
        val hd = u.heading
        if (u.tracking == TrackingState.TRACKING && cam != null && hd != null) {
            if (calibrating) return
            if (!aligner.aligned) aligner.start(cam, hd, u.cameraHeightM.toDouble(), walker.pos, compassMap() ?: walkHeading(), curFloor)
            for (o in u.landmarks) aligner.onLandmark(o.id, o.arPos, o.arYaw, o.distanceM.toDouble(), u.timeMs)
            aligner.correct(cam, hd, curFloor, compassMap())
            arPose = aligner.pose(cam, hd, curFloor)
            arTracking = true
            arEverTracked = true
            arLostSince = 0L
            // no barometer: the camera height tells the floor
            if (!barometer.available) aligner.floorAt(u.cameraHeightM.toDouble())?.let { detectedFloor = it }
        } else {
            arTracking = false
            if (arLostSince == 0L) arLostSince = u.timeMs
            // a long loss can move ARCore's origin: line up again from where the steps put you
            if (u.timeMs - arLostSince > 4000) aligner.reset()
        }
    }

    DisposableEffect(Unit) {
        tracker.start(); compass.start(); barometer.start()
        onDispose { tracker.stop(); compass.stop(); barometer.stop(); vm.speaker.stop() }
    }

    LaunchedEffect(startRoute) { map.setRoute(startRoute); map.setFloor(start.floor); map.view("follow") }
    // a barometer that already sees the new floor confirms the change by itself
    LaunchedEffect(detectedFloor, pendingFloor) {
        val p = pendingFloor ?: return@LaunchedEffect
        if (detectedFloor == p) switchFloor(p)
    }
    LaunchedEffect(calibrating) {
        if (!calibrating) say(startInstruction(lang, venue, start, route) + " " +
            I18n.t(lang, "v_total", "d" to NavPhrases.metresVoice(lang, route.length), "p" to dest.name))
    }
    LaunchedEffect(tap) {
        val p = tap ?: return@LaunchedEffect
        walker.place(p); shown = walker.pos
        applyWrongWay(monitor.update(walker.pos, curFloor, null))
        onTapUsed()
    }
    LaunchedEffect(arrived) {
        if (arrived) { demo = null; map.setTrail(null); map.setWrong(false); vm.learnGeo(dest.id); vm.haptics.success(); map.view("route") }
    }
    var floorWarned by remember(startRoute) { mutableStateOf(false) }
    LaunchedEffect(detectedFloor) {
        val f = detectedFloor ?: return@LaunchedEffect
        if (f != curFloor && pendingFloor == null && !floorWarned) {
            floorWarned = true
            vm.haptics.warning()
            say(I18n.t(lang, "floor_mismatch", "f" to NavPhrases.floorName(lang, f), "g" to NavPhrases.floorName(lang, curFloor)))
        } else if (f == curFloor) floorWarned = false
    }
    // 30 Hz: demo steps, ease the dot, guidance + voice, buzz while going the wrong way, feed the 3D view
    LaunchedEffect(startRoute) {
        var last = System.nanoTime()
        var demoAcc = 0.0
        var demoFloorS = 0.0
        var lastMonitorPos = walker.pos
        var lastBuzz = 0L
        val turnBuzzed = HashSet<Double>()
        while (true) {
            delay(33)
            val nowNs = System.nanoTime()
            val dt = (nowNs - last) / 1e9
            last = nowNs
            // demo: ride the stairs / escalator for a couple of seconds
            val waiting = g.nextManeuver?.takeIf { g.phase == NavPhase.FLOOR_CHANGE && it.kind == ManeuverKind.FLOOR_CHANGE }?.toFloor
            if (demo != null && waiting != null) {
                demoFloorS += dt
                if (demoFloorS > 2.5) { demoFloorS = 0.0; switchFloor(waiting) }
            } else demoFloorS = 0.0
            if (demo != null && !arrived && !calibrating && waiting == null) {
                demoAcc += dt
                if (demoAcc >= DEMO_STEP_S) { demoAcc -= DEMO_STEP_S; stepRef.value() }
            }
            // AR moves you: snap its position onto the corridors, check the way every half metre
            if (arOn && arTracking && demo == null && !calibrating && waiting == null && !arrived) arPose?.let { p ->
                walker.place(p.pos)
                if (walker.pos.distanceTo(lastMonitorPos) >= 0.5) {
                    val moveHeading = (walker.pos - lastMonitorPos).heading()
                    lastMonitorPos = walker.pos
                    applyWrongWay(monitor.update(walker.pos, curFloor, moveHeading))
                }
            }
            val h = if (calibrating) route.headingAt(0.0) else walkHeading()
            shownHeading = h
            shown = shown + (walker.pos - shown) * (1 - exp(-dt * 4.0))
            val now = System.currentTimeMillis()
            val st = engine.update(Pose(shown, h, curFloor), TrackingQuality.GOOD, now)
            g = st
            if (!calibrating) announcer.update(st, wrong, now)?.let(::say)
            // one-shot flags: announced once, then cleared
            wrong?.let { w -> if (w.justWentWrong || w.justRecovered) wrong = w.copy(justWentWrong = false, justRecovered = false) }
            if (wrong?.wrong == true && now - lastBuzz > 3000) { lastBuzz = now; vm.haptics.warning() }
            val m = st.nextManeuver
            if (m != null && m.kind == ManeuverKind.TURN && (st.distToManeuverM ?: 99.0) < 2.5 && turnBuzzed.add(m.atS)) vm.haptics.turn()
            map.setUser(shown, h, curFloor)
            // the route ahead, drawn on the real floor: a chevron every 1.2 m for the next 15 m, flowing forward
            arRouteRel = if (arOn && arTracking) arPose?.let { ap ->
                Angles.norm((route.pointAt(st.progressM + 2.5) - ap.pos).heading() - ap.headingDeg)
            } else null
            overlay.scene = if (arOn && aligner.aligned) {
                val list = ArrayList<Float>()
                val phase = (now % 1500) / 1500.0 * CHEVRON_GAP_M
                var sAt = st.progressM + 0.8 + phase
                val until = minOf(route.length - 0.3, st.progressM + AR_AHEAD_M)
                while (sAt < until) {
                    if (route.floorAt(sAt) == curFloor) {
                        val q = aligner.toAr(route.pointAt(sAt))
                        val a = aligner.toArHeading(route.headingAt(sAt))
                        if (q != null && a != null) { list += q.x.toFloat(); list += (-q.y).toFloat(); list += a.toFloat() }
                    }
                    sAt += CHEVRON_GAP_M
                }
                val dest = if (route.end.floor == curFloor && route.length - st.progressM < 25)
                    aligner.toAr(route.end.pos)?.let { floatArrayOf(it.x.toFloat(), (-it.y).toFloat()) } else null
                val floorY = aligner.startY - CAMERA_HEIGHT_M + (curFloor - aligner.startFloor) * venue.floorHeightM
                ArScene(list.toFloatArray(), floorY.toFloat(), ArOverlay.GREEN, dest)
            } else null
            map.setProgress(st.progressM)
        }
    }

    val isWrong = wrong?.wrong == true
    Box(Modifier.fillMaxSize()) {
    if (arMode) ArCameraLayer(vm, venue, overlay, ::onAr)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        // ---- instruction banner ----
        val bannerColor = when { arrived -> R360.Go; isWrong -> R360.Bad; else -> R360.Surface.copy(alpha = 0.96f) }
        val onBanner = if (arrived) Color(0xFF04210F) else if (isWrong) Color.White else R360.Text
        Surface(shape = RoundedCornerShape(20.dp), color = bannerColor, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!arrived) {
                    val turn = if (isWrong) 180.0 else g.nextManeuver?.takeIf { (g.distToManeuverM ?: 99.0) < 12 && it.kind == ManeuverKind.TURN }?.turnDeg ?: 0.0
                    TurnArrow(turn, 56.dp, if (isWrong) Color(0xFF7F1D1D) else R360.Go)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    val m = g.nextManeuver
                    val d = g.distToManeuverM
                    Text(
                        when {
                            arrived -> tr("arrived")
                            isWrong -> tr("wrong_way")
                            m != null && d != null -> NavPhrases.next(lang, m, d, fromFloor = curFloor)
                            else -> tr("walk_line")
                        },
                        style = MaterialTheme.typography.titleLarge, color = onBanner,
                    )
                    Text(
                        when {
                            arrived -> dest.name
                            isWrong -> tr("wrong_way_body")
                            else -> g.towardName?.let { tr("toward", "p" to it) } ?: tr("to_dest", "p" to dest.name)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = onBanner.copy(alpha = 0.8f),
                    )
                }
            }
        }
        // ---- AR: a small map in the corner, and what AR needs from you ----
        if (arMode) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (!arTracking) Pill(tr(if (arEverTracked) "ar_lost" else "ar_hint"), R360.Warn)
                    // the arrows are off-screen: say which way to turn the phone
                    arRouteRel?.takeIf { abs(it) > 35 && !arrived }?.let { rel ->
                        Surface(shape = RoundedCornerShape(16.dp), color = R360.Surface.copy(alpha = 0.92f)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                TurnArrow(if (rel > 0) 90.0 else -90.0, 40.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(tr(if (rel > 0) "ar_turn_right" else "ar_turn_left"), color = R360.Text, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Box(Modifier.size(110.dp, 150.dp).clip(RoundedCornerShape(14.dp)).background(R360.Surface.copy(alpha = 0.85f))) {
                    MapCanvas(venue, curFloor, MapOverlay(route = route, user = Pose(shown, shownHeading, curFloor), selectedPin = dest,
                        showLabels = false, showLandmarks = false), Modifier.fillMaxSize().padding(4.dp))
                }
            }
        }
        // ---- curFloor (barometer) ----
        detectedFloor?.let { f ->
            Spacer(Modifier.height(6.dp))
            if (f != curFloor) Pill(tr("floor_mismatch", "f" to NavPhrases.floorName(lang, f), "g" to NavPhrases.floorName(lang, curFloor)), R360.Warn)
            else Pill(NavPhrases.floorName(lang, f), R360.Info)
        }
        if (debug) Text(
            "steps=$steps pos=(%.1f, %.1f) north=%s compass=%s wrong=%s lat=%.1f h=%.1fm".format(
                walker.pos.x, walker.pos.y, calibrator.northDeg?.roundToInt(), compass.headingDeg?.roundToInt(),
                isWrong, wrong?.lateralM ?: 0.0, estimator.heightM),
            color = Color(0xFF9EF0B5), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp).background(Color.Black.copy(alpha = 0.6f)).padding(6.dp))
        Spacer(Modifier.weight(1f))
        if (freeCamera) OutlinedButton(onClick = onRecenter, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(tr("recenter"), color = R360.Text)
        }
        Spacer(Modifier.height(8.dp))
        // ---- bottom: compass calibration, arrival, or the trip bar ----
        Surface(shape = RoundedCornerShape(20.dp), color = R360.Surface.copy(alpha = 0.96f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                when {
                    calibrating -> {
                        Text(tr("calib_title"), style = MaterialTheme.typography.titleLarge, color = R360.Text)
                        Text(tr("calib_body"), style = MaterialTheme.typography.bodyMedium, color = R360.Muted)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { calibrating = false }) { Text(tr("calib_skip"), color = R360.Text) }
                            Button(onClick = {
                                compass.headingDeg?.let { c ->
                                    calibrator.calibrate(c, route.headingAt(0.0))
                                    calibrator.northDeg?.let(vm::saveNorth)
                                }
                                calibrating = false
                            }, colors = ButtonDefaults.buttonColors(containerColor = R360.Go), modifier = Modifier.weight(1f)) { Text(tr("calib_ok")) }
                        }
                    }
                    pendingFloor != null -> {
                        Text(g.nextManeuver?.let { NavPhrases.floorChange(lang, it, curFloor) } ?: "", style = MaterialTheme.typography.titleLarge, color = R360.Text)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { switchFloor(pendingFloor) }, colors = ButtonDefaults.buttonColors(containerColor = R360.Go),
                            modifier = Modifier.fillMaxWidth()) { Text(tr("on_floor", "f" to NavPhrases.floorName(lang, pendingFloor))) }
                    }
                    arrived -> {
                        Text(tr("right_here", "p" to dest.name), style = MaterialTheme.typography.titleMedium, color = R360.Text)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = R360.Go), modifier = Modifier.fillMaxWidth()) { Text(tr("done")) }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("left", "d" to NavPhrases.metres(lang, g.remainingM)), style = MaterialTheme.typography.headlineMedium,
                                    color = if (isWrong) R360.Bad else R360.Go)
                                Text(walkTime(lang, g.remainingM), style = MaterialTheme.typography.bodyMedium, color = R360.Muted)
                            }
                            OutlinedButton(onClick = onEnd) { Text(tr("end"), color = R360.Bad) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (arSupported) FilterChip(arMode, { onArMode(!arMode) }, label = { Text(tr("ar_mode")) })
                            FilterChip(voiceOn, { vm.setVoice(!voiceOn) }, label = { Text(if (voiceOn) tr("voice_on") else tr("voice_off")) })
                            FilterChip(demo == Demo.WALK, { demo = if (demo == Demo.WALK) null else Demo.WALK }, label = { Text(tr("demo_walk")) })
                            FilterChip(demo == Demo.WRONG, { demo = if (demo == Demo.WRONG) null else Demo.WRONG }, label = { Text(tr("demo_wrong")) })
                        }
                        Text(if (tracker.available) tr("steps_hint") else tr("no_steps"),
                            style = MaterialTheme.typography.labelMedium, color = R360.Muted)
                    }
                }
            }
        }
    }
}
}

/** The AR camera behind the guidance: ARCore session + the route drawn on the floor. */
@Composable
private fun ArCameraLayer(vm: AppViewModel, venue: Venue, overlay: ArOverlay, onUpdate: (ArUpdate) -> Unit) {
    CameraPermissionGate {
        var err by remember { mutableStateOf<String?>(null) }
        val engine = rememberArEngine { err = it }
        val cb = rememberUpdatedState(onUpdate)
        val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
        LaunchedEffect(engine) {
            engine.setLandmarks(landmarkImages(vm.repo, venue)) // printed markers, if any, correct AR exactly
            engine.overlay = overlay
            engine.onUpdate = { u -> main.post { cb.value(u) } }
        }
        DisposableEffect(engine) { onDispose { engine.onUpdate = null; engine.overlay = null } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.ui.viewinterop.AndroidView(factory = { engine.surface }, modifier = Modifier.fillMaxSize())
            err?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
        }
    }
}

/** Big arrow for the next turn: 0 = straight on, ±90 = right/left, 180 = turn around. */
@Composable
private fun TurnArrow(turnDeg: Double, size: Dp, background: Color = R360.Go) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(background), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.7f).rotate(turnDeg.coerceIn(-180.0, 180.0).toFloat())) {
            val w = this.size.width
            val h = this.size.height
            val p = Path().apply {
                moveTo(w / 2, 0f); lineTo(w, h * 0.45f); lineTo(w * 0.66f, h * 0.45f); lineTo(w * 0.66f, h)
                lineTo(w * 0.34f, h); lineTo(w * 0.34f, h * 0.45f); lineTo(0f, h * 0.45f); close()
            }
            drawPath(p, Color.White)
        }
    }
}

@Composable
fun PinRow(vm: AppViewModel, p: VisualPin, trailing: String? = null, floor: String? = null, onClick: () -> Unit) {
    val venue by vm.venue.collectAsState()
    val bmp = remember(p.image, venue.revision) { vm.repo.loadBitmap(venue.id, p.image, 256) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(bmp, p.name, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = R360.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(listOfNotNull(floor, p.description.ifBlank { p.category }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = R360.Muted, maxLines = 1)
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = R360.Go) }
    }
}
