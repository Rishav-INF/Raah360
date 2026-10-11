package com.raah360.nav

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.raah360.ar.ArUpdate
import com.raah360.core.GuidanceState
import com.raah360.core.Localizer
import com.raah360.core.ManeuverKind
import com.raah360.core.NavPhase
import com.raah360.core.NavigationSession
import com.raah360.core.Pose
import com.raah360.core.Route
import com.raah360.core.Sector
import com.raah360.core.TrackingQuality
import com.raah360.core.Venue
import com.raah360.core.VerifyResult
import com.raah360.core.VerifyState
import com.raah360.core.VisualPin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NavUi(
    val guidance: GuidanceState = GuidanceState(NavPhase.LOCALIZING, headline = "SCAN A LANDMARK"),
    val verify: VerifyState? = null,
    val pose: Pose? = null,
    val route: Route? = null,
    val quality: TrackingQuality = TrackingQuality.NOT_LOCALIZED,
    val trackingText: String = "Starting camera…",
    val hint: String? = null,
    val lastLandmark: String? = null,
    val debug: String = "",
    val tick: Long = 0,
)

/**
 * Glue between pose sources (ARCore or the Simulator) and the core NavigationSession.
 * Thread-safe enough for GL-thread producers: it only writes to a StateFlow.
 */
class Navigator(
    val venue: Venue,
    val pin: VisualPin,
    private val speaker: Speaker?,
    private val haptics: Haptics?,
) {
    val session = NavigationSession(venue, pin)
    val localizer = Localizer(venue)
    private val _ui = MutableStateFlow(NavUi())
    val ui: StateFlow<NavUi> = _ui

    private var pausedSince = -1L
    private var lastPose: Pose? = null
    private var lastSector: Sector? = null
    private var lastUiMs = 0L
    private var lastLandmarkName: String? = null
    private var lastVerify: VerifyResult? = null
    private var lastManeuverBuzz: Double? = null

    /** True while the arrival screen needs camera signatures. */
    val wantsSignatures: Boolean get() = _ui.value.guidance.phase == NavPhase.ARRIVED && pin.signature != null

    fun restart() {
        session.restart(); localizer.reset(); lastPose = null; lastSector = null; lastVerify = null
        _ui.value = NavUi()
    }

    /** ARCore frame (GL thread). */
    fun onAr(u: ArUpdate) {
        // 1) landmarks → alignment
        for (o in u.landmarks) {
            if (localizer.onLandmark(o.id, o.arPos, o.arYaw, o.distanceM.toDouble(), u.timeMs)) {
                lastLandmarkName = venue.landmark(o.id)?.name
            }
        }
        // 2) tracking quality
        val quality: TrackingQuality
        var trackingText: String
        when (u.tracking) {
            TrackingState.TRACKING -> {
                pausedSince = -1
                quality = if (localizer.isLocalized) TrackingQuality.GOOD else TrackingQuality.NOT_LOCALIZED
                trackingText = if (localizer.isLocalized) "Navigation active" else "Scan a nearby landmark"
            }
            TrackingState.PAUSED -> {
                if (pausedSince < 0) pausedSince = u.timeMs
                if (u.timeMs - pausedSince > 3000 && localizer.isLocalized) {
                    localizer.reset() // AR frame may have reset: need a landmark again
                }
                quality = when {
                    !localizer.isLocalized && lastPose != null -> TrackingQuality.LOST
                    !localizer.isLocalized -> TrackingQuality.NOT_LOCALIZED
                    else -> TrackingQuality.UNSTABLE
                }
                trackingText = when (u.failure) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — find more light"
                    TrackingFailureReason.EXCESSIVE_MOTION -> "Tracking unstable — move phone slowly"
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at walls or objects, not blank surfaces"
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera busy"
                    else -> "Tracking unstable — move phone slowly"
                }
                if (quality == TrackingQuality.LOST) trackingText = "Location lost — scan a nearby mapped landmark"
            }
            else -> {
                quality = if (lastPose != null) TrackingQuality.LOST else TrackingQuality.NOT_LOCALIZED
                trackingText = "Location lost — scan a nearby mapped landmark"
            }
        }
        // 3) pose on map
        var pose: Pose? = null
        if (u.camPos != null && u.heading != null) pose = localizer.mapPose(u.camPos, u.heading)
        if (pose == null && quality == TrackingQuality.UNSTABLE) pose = lastPose
        if (pose != null) lastPose = pose
        val hint = when {
            quality == TrackingQuality.GOOD && localizer.metresSinceFix > 35 -> "Scan any landmark you pass to keep accuracy"
            else -> null
        }
        feed(pose, quality, u.timeMs, u.signature, trackingText, hint)
    }

    /** Simulator or AR: one guidance step. */
    fun feed(pose: Pose?, quality: TrackingQuality, now: Long, signature: FloatArray? = null, trackingText: String = "Navigation active", hint: String? = null) {
        val f = session.update(pose, quality, now, signature)
        f.speech?.let { speaker?.say(it.text, it.urgent) }

        // haptics on meaningful changes only
        val g = f.guidance
        if (g.sector != null && lastSector != null && g.sector != lastSector) haptics?.tick()
        lastSector = g.sector
        val m = g.nextManeuver
        if (m != null && m.kind == ManeuverKind.TURN && (g.distToManeuverM ?: 99.0) < 2.5 && lastManeuverBuzz != m.atS) {
            lastManeuverBuzz = m.atS; haptics?.turn()
        }
        if (f.verify?.result != lastVerify) {
            when (f.verify?.result) {
                VerifyResult.EXACT_ARRIVAL -> haptics?.success()
                VerifyResult.WRONG_SPOT -> haptics?.warning()
                else -> Unit
            }
            lastVerify = f.verify?.result
        }

        // UI at ~20 Hz is plenty
        if (now - lastUiMs < 50 && f.speech == null && !f.routeChanged) return
        lastUiMs = now
        val dbg = buildString {
            pose?.let { append("map (%.2f, %.2f) h=%.0f° fl=%d".format(it.pos.x, it.pos.y, it.headingDeg, it.floor)) }
            append("\nrel=%s lat=%.2f prog=%.1f/%.1f".format(g.relativeDeg?.let { "%.0f°".format(it) } ?: "-", g.lateralM, g.progressM, f.route?.length ?: 0.0))
            append("\nfix=${localizer.lastFix?.landmarkId ?: "-"} corr=%.2fm since=%.0fm reroutes=${session.reroutes}".format(localizer.lastCorrectionM, localizer.metresSinceFix))
            f.verify?.similarity?.let { append("\nsim=%.3f".format(it)) }
        }
        _ui.value = NavUi(g, f.verify, pose, f.route, quality, trackingText, hint, lastLandmarkName, dbg, now)
    }
}
