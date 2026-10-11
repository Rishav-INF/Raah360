package com.raah360

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raah360.core.I18n
import com.raah360.core.Lang
import com.raah360.core.Vec2
import com.raah360.core.Venue
import com.raah360.core.withGeoFix
import com.raah360.data.VenueRepository
import com.raah360.nav.GpsTracker
import com.raah360.nav.Haptics
import com.raah360.nav.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where a trip starts: a known place, or a spot tapped on the map. */
data class StartPoint(val name: String, val pos: Vec2, val floor: Int, val pinId: String? = null)

sealed interface Screen {
    /** The list of 3D maps near you. */
    data object Home : Screen
    /** One building's 3D map: From / To / Start. */
    data object Map : Screen
    data class PinDetail(val pinId: String) : Screen
    data class Navigate(val pinId: String) : Screen
    data class Simulator(val pinId: String) : Screen
    data object Mapper : Screen
    data object Markers : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = VenueRepository(app)
    private val prefs = app.getSharedPreferences("raah360", 0)

    private val _venue = MutableStateFlow(repo.load(prefs.getString("venue", null)))
    val venue: StateFlow<Venue> = _venue

    private val stack = ArrayDeque<Screen>().apply { add(Screen.Home) }
    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen

    private val _voice = MutableStateFlow(prefs.getBoolean("voice", true))
    val voice: StateFlow<Boolean> = _voice
    private val _debug = MutableStateFlow(prefs.getBoolean("debug", false))
    val debug: StateFlow<Boolean> = _debug
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _lang = MutableStateFlow(Lang.of(prefs.getString("lang", null)))
    /** The visitor's language: screen text and voice. */
    val lang: StateFlow<Lang> = _lang

    val speaker = Speaker(app).also {
        it.enabled = _voice.value
        it.setLanguage(_lang.value.tag)
        it.onVoiceAvailability = { ok ->
            val l = _lang.value
            if (!ok && l != Lang.EN) _message.value = I18n.t(l, "voice_missing", "l" to l.nativeName)
        }
    }

    fun setLang(l: Lang) {
        _lang.value = l
        prefs.edit().putString("lang", l.tag).apply()
        speaker.setLanguage(l.tag)
    }

    /** Remember which way the map faces (from the one-tap compass calibration). */
    fun saveNorth(northDeg: Double) {
        val v = _venue.value
        updateVenue(v.copy(northDeg = northDeg), undoable = false)
    }
    val haptics = Haptics(app)
    val gps = GpsTracker(app)

    /** The trip being planned on the map; kept here so it survives a visit to another screen. */
    val tripFrom = MutableStateFlow<StartPoint?>(null)
    val tripTo = MutableStateFlow<String?>(null)

    /** Destination opened from a shared link, picked up by the map screen. */
    private val _pendingDestination = MutableStateFlow<String?>(null)
    val pendingDestination: StateFlow<String?> = _pendingDestination
    fun consumePendingDestination() { _pendingDestination.value = null }

    private val undo = ArrayDeque<Venue>()

    fun go(s: Screen) { stack.addLast(s); _screen.value = s }
    fun replace(s: Screen) { if (stack.isNotEmpty()) stack.removeLast(); go(s) }
    fun back(): Boolean {
        speaker.stop()
        if (stack.size <= 1) return false
        stack.removeLast(); _screen.value = stack.last(); return true
    }
    fun home() { stack.clear(); go(Screen.Home) }

    fun setVoice(on: Boolean) {
        _voice.value = on; speaker.enabled = on
        if (!on) speaker.stop()
        prefs.edit().putBoolean("voice", on).apply()
    }

    fun setDebug(on: Boolean) { _debug.value = on; prefs.edit().putBoolean("debug", on).apply() }

    fun toast(msg: String?) { _message.value = msg }

    /** Every Mapper edit goes through here: saved immediately, undoable. */
    fun updateVenue(v: Venue, undoable: Boolean = true) {
        if (undoable) {
            undo.addLast(_venue.value)
            while (undo.size > 30) undo.removeFirst()
        }
        _venue.value = v
        prefs.edit().putString("venue", v.id).apply()
        viewModelScope.launch(Dispatchers.IO) { repo.save(v) }
    }

    /**
     * Remember where [pinId] is in GPS terms: called when someone confirms "I'm here" or arrives.
     * Returns true when the fix was good enough to keep.
     */
    fun learnGeo(pinId: String): Boolean {
        val loc = gps.usableFix() ?: return false
        val v = _venue.value
        val pins = v.pins.map { if (it.id == pinId) it.withGeoFix(loc.latitude, loc.longitude, loc.accuracy.toDouble()) else it }
        updateVenue(v.copy(pins = pins, lat = v.lat ?: loc.latitude, lng = v.lng ?: loc.longitude), undoable = false)
        return true
    }

    /** All maps on the phone, for the home list (reads files: call off the main thread). */
    fun catalog(): List<Venue> = repo.catalog()

    /** Open a building: its map becomes the current one and its trip planning starts fresh. */
    fun openVenue(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val v = repo.load(id)
            launch(Dispatchers.Main) {
                if (v.id != _venue.value.id) { tripFrom.value = null; tripTo.value = null; undo.clear() }
                _venue.value = v
                prefs.edit().putString("venue", v.id).apply()
                go(Screen.Map)
            }
        }
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        updateVenue(prev, undoable = false)
        return true
    }

    fun resetToSurvey() {
        viewModelScope.launch(Dispatchers.IO) {
            val v = repo.resetToSeed(_venue.value.id)
            undo.clear()
            _venue.value = v
            _message.value = "Map reset to the original survey"
        }
    }

    fun exportVenue(onReady: (File) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = repo.exportZip(_venue.value)
            launch(Dispatchers.Main) { onReady(f) }
        }
    }

    fun importVenue(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val v = repo.importZip(uri)
                undo.clear()
                _venue.value = v
                prefs.edit().putString("venue", v.id).apply()
                _message.value = "Imported ${v.name}"
            } catch (e: Exception) {
                _message.value = "Import failed: ${e.message}"
            }
        }
    }

    /** raah360://pin/<venueId>/<pinId> */
    /** raah360://pin/<venueId>/<pinId>: opens that building with the place as the destination. */
    fun openDeepLink(uri: Uri?) {
        if (uri == null || uri.scheme != "raah360" || uri.host != "pin") return
        val parts = uri.pathSegments
        if (parts.size < 2) return
        val (venueId, pinId) = parts[0] to parts[1]
        if (venueId != _venue.value.id) {
            // the shipped maps are copied in on first use, so make sure they are there
            val ids = repo.listVenueIds().ifEmpty { repo.catalog().map { it.id } }
            val known = venueId in ids || repo.catalog().any { it.id == venueId }
            if (!known) { _message.value = I18n.t(_lang.value, "map_missing"); return }
            _venue.value = repo.load(venueId)
            prefs.edit().putString("venue", venueId).apply()
            tripFrom.value = null
        }
        if (_venue.value.pin(pinId) == null) { _message.value = I18n.t(_lang.value, "map_missing"); return }
        home(); go(Screen.Map); _pendingDestination.value = pinId
    }

    /** Finds a Raah360 place link anywhere in a pasted message. */
    fun sharedLinkIn(text: CharSequence?): Uri? =
        text?.let { SHARE_LINK.find(it)?.value }?.let(Uri::parse)

    /** "Meet me at Room F · Atrium, Floor 1" + the link, in the visitor's language. */
    fun shareText(pin: com.raah360.core.VisualPin): String {
        val v = _venue.value
        val floor = com.raah360.core.NavPhrases.floorName(_lang.value, pin.floor)
        return I18n.t(_lang.value, "share_text", "p" to pin.name, "v" to v.name, "f" to floor, "link" to "raah360://pin/${v.id}/${pin.id}")
    }

    companion object {
        private val SHARE_LINK = Regex("raah360://pin/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
    }

    override fun onCleared() {
        gps.stop()
        speaker.shutdown()
    }
}
