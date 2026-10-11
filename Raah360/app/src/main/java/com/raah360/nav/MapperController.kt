package com.raah360.nav

import com.google.ar.core.TrackingState
import com.raah360.ar.ArUpdate
import com.raah360.core.Localizer
import com.raah360.core.Pose
import com.raah360.core.Vec2
import com.raah360.core.Venue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SeenLandmark(
    val id: String,
    val name: String,
    val distanceM: Float,
    val located: Boolean,
    /** Where the landmark is on the map according to the current alignment. */
    val measuredPos: Vec2?,
    val measuredYaw: Double?,
)

data class MapperUi(
    val tracking: TrackingState = TrackingState.STOPPED,
    val aligned: Boolean = false,
    val pose: Pose? = null,
    val seen: List<SeenLandmark> = emptyList(),
    val lastFixName: String? = null,
    val correctionM: Double = 0.0,
)

/** Mapper-side processing of AR frames (GL thread → StateFlow). */
class MapperController(initial: Venue) {
    @Volatile var venue: Venue = initial
        private set
    val localizer = Localizer(initial)
    private val _ui = MutableStateFlow(MapperUi())
    val ui: StateFlow<MapperUi> = _ui
    @Volatile var lastAr: ArUpdate? = null
        private set
    private var lastUiMs = 0L
    private var lastSeen: List<SeenLandmark> = emptyList()
    private var lastSeenMs = 0L

    fun setVenue(v: Venue) {
        venue = v
        localizer.venue = v
    }

    fun onAr(u: ArUpdate) {
        lastAr = u
        val v = venue
        for (o in u.landmarks) {
            val lm = v.landmark(o.id) ?: continue
            // Measured landmarks always align. Survey-estimated ones may only bootstrap the
            // very first alignment; afterwards they are being measured, not trusted.
            val usable = lm.located || !localizer.isLocalized
            if (usable) localizer.onLandmark(o.id, o.arPos, o.arYaw, o.distanceM.toDouble(), u.timeMs)
        }
        if (u.tracking != TrackingState.TRACKING && u.tracking != TrackingState.PAUSED) localizer.reset()
        val pose = if (u.camPos != null && u.heading != null) localizer.mapPose(u.camPos, u.heading) else null
        if (u.landmarks.isNotEmpty()) {
            lastSeen = u.landmarks.map { o ->
                val lm = v.landmark(o.id)
                SeenLandmark(o.id, lm?.name ?: o.id, o.distanceM, lm?.located == true, localizer.toMap(o.arPos), localizer.toMapHeading(o.arYaw))
            }
            lastSeenMs = u.timeMs
        } else if (u.timeMs - lastSeenMs > 1500) lastSeen = emptyList()

        if (u.timeMs - lastUiMs < 100) return
        lastUiMs = u.timeMs
        _ui.value = MapperUi(
            u.tracking, localizer.isLocalized, pose, lastSeen,
            localizer.lastFix?.landmarkId?.let { v.landmark(it)?.name }, localizer.lastCorrectionM
        )
    }

    /** Define the map frame: "I'm standing at [mapPos] facing [mapHeading]". */
    fun setOriginHere(mapPos: Vec2, mapHeading: Double, floor: Int): Boolean {
        val u = lastAr ?: return false
        val p = u.camPos ?: return false
        val h = u.heading ?: return false
        localizer.setOriginHere(p, h, mapPos, mapHeading, floor)
        return true
    }
}
