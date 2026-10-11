package com.raah360.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** The eight stable direction sectors, clockwise from FRONT. Angles are relative to the user. */
enum class Sector(val centerDeg: Double, val label: String, val headline: String) {
    FRONT(0.0, "Front", "GO STRAIGHT"),
    FRONT_RIGHT(45.0, "Front-right", "SLIGHT RIGHT"),
    RIGHT(90.0, "Right", "TURN RIGHT"),
    BACK_RIGHT(135.0, "Back-right", "TURN SHARP RIGHT"),
    BACK(180.0, "Back", "TURN AROUND"),
    BACK_LEFT(-135.0, "Back-left", "TURN SHARP LEFT"),
    LEFT(-90.0, "Left", "TURN LEFT"),
    FRONT_LEFT(-45.0, "Front-left", "SLIGHT LEFT");

    companion object {
        /** Plain quantisation: ±22.5° around each centre. */
        fun of(relDeg: Double): Sector {
            val a = Angles.norm(relDeg)
            val idx = Math.floorMod(((a + 22.5) / 45.0).let { kotlin.math.floor(it).toInt() }, 8)
            return listOf(FRONT, FRONT_RIGHT, RIGHT, BACK_RIGHT, BACK, BACK_LEFT, LEFT, FRONT_LEFT)[idx]
        }
    }
}

enum class TrackingQuality { GOOD, UNSTABLE, LOST, NOT_LOCALIZED }

enum class NavPhase { LOCALIZING, NAVIGATING, UNSTABLE, LOST, OFF_ROUTE, FLOOR_CHANGE, ARRIVED }

data class Pose(val pos: Vec2, val headingDeg: Double, val floor: Int)

data class GuidanceConfig(
    val lookAheadM: Double = 3.0,
    val minLookAheadM: Double = 1.2,
    val offRouteM: Double = 3.5,
    val offRouteHoldMs: Long = 1500,
    val arriveM: Double = 1.6,
    val windowBackM: Double = 3.0,
    val windowAheadM: Double = 12.0,
    val smoothingTauS: Double = 0.25,
    val hysteresisDeg: Double = 10.0,
    val switchHoldMs: Long = 250,
)

data class GuidanceState(
    val phase: NavPhase,
    val sector: Sector? = null,
    /** Smoothed angle from the user's heading to the look-ahead point (+ = right). */
    val relativeDeg: Double? = null,
    val headline: String = "",
    /** e.g. "Turn right in 6 m" */
    val nextAction: String = "",
    val nextActionM: Double? = null,
    val towardName: String? = null,
    val remainingM: Double = 0.0,
    val progressM: Double = 0.0,
    val lateralM: Double = 0.0,
    val lookAhead: Vec2? = null,
    val projected: Vec2? = null,
    val nextManeuver: Maneuver? = null,
    val distToManeuverM: Double? = null,
    val floor: Int? = null,
)

/**
 * Turns an angle that wobbles a few degrees into a sector that only changes when the user
 * really turned: circular EMA smoothing + boundary hysteresis + a short hold time.
 */
class SectorStabilizer(private val cfg: GuidanceConfig = GuidanceConfig()) {
    private var sx = 0.0
    private var sy = 0.0
    private var hasValue = false
    private var lastMs = 0L
    var current: Sector? = null
        private set
    private var candidate: Sector? = null
    private var candidateSince = 0L
    var smoothedDeg: Double = 0.0
        private set

    fun reset() {
        hasValue = false; current = null; candidate = null
    }

    fun update(rawDeg: Double, nowMs: Long, frozen: Boolean = false): Sector {
        val r = Math.toRadians(rawDeg)
        if (!hasValue) {
            sx = cos(r); sy = sin(r); hasValue = true
        } else {
            val dt = ((nowMs - lastMs).coerceAtLeast(0)) / 1000.0
            val k = 1.0 - exp(-dt / cfg.smoothingTauS)
            sx += (cos(r) - sx) * k
            sy += (sin(r) - sy) * k
        }
        lastMs = nowMs
        smoothedDeg = Math.toDegrees(atan2(sy, sx))
        val cur = current
        if (cur == null) {
            current = Sector.of(smoothedDeg); return current!!
        }
        if (frozen) return cur
        // stay in the current sector while within its half-width + hysteresis margin
        if (Angles.diff(smoothedDeg, cur.centerDeg) <= 22.5 + cfg.hysteresisDeg) {
            candidate = null
            return cur
        }
        val next = Sector.of(smoothedDeg)
        val bigJump = Angles.diff(next.centerDeg, cur.centerDeg) >= 90.0
        if (candidate != next) {
            candidate = next; candidateSince = nowMs
        }
        if (bigJump || nowMs - candidateSince >= cfg.switchHoldMs) {
            current = next; candidate = null
        }
        return current!!
    }
}

/**
 * Converts (pose on map, active route) into stable guidance. Never points at the final pin:
 * it points at a look-ahead point a few metres further ALONG the walkable route.
 */
class GuidanceEngine(route: Route, val cfg: GuidanceConfig = GuidanceConfig()) {
    var route: Route = route
        private set
    private val stabilizer = SectorStabilizer(cfg)
    private var progress = 0.0
    private var offSince = -1L
    private var started = false
    var arrived = false
        private set

    fun reset(newRoute: Route) {
        route = newRoute; progress = 0.0; offSince = -1L; started = false; arrived = false
        stabilizer.reset()
    }

    val progressM get() = progress

    /** Continue past a floor change (after the user re-localised on the new floor). */
    fun skipTo(s: Double) {
        progress = s.coerceIn(0.0, route.length)
    }

    fun update(pose: Pose?, quality: TrackingQuality, nowMs: Long): GuidanceState {
        val remaining = route.length - progress
        if (quality == TrackingQuality.LOST) {
            stabilizer.reset()
            return GuidanceState(NavPhase.LOST, headline = "LOCATION LOST", nextAction = "Scan a nearby mapped landmark", remainingM = remaining, progressM = progress)
        }
        if (quality == TrackingQuality.NOT_LOCALIZED || pose == null) {
            stabilizer.reset()
            return GuidanceState(NavPhase.LOCALIZING, headline = "SCAN A LANDMARK", nextAction = "Point the camera at a mapped landmark", remainingM = remaining, progressM = progress)
        }

        // floor check: if the route expects another floor here, we are in a floor change
        val expectedFloor = route.floorAt(progress)
        val pendingFloorChange = route.maneuvers.firstOrNull { it.kind == ManeuverKind.FLOOR_CHANGE && it.atS >= progress - 0.5 && it.atS - progress < 2.0 }
        if (pose.floor != expectedFloor && pendingFloorChange != null && pendingFloorChange.toFloor == pose.floor) {
            // user already re-localised on the next floor: continue from there
            progress = pendingFloorChange.atS + 1e-6
        }

        val first = !started
        val proj = if (first) {
            route.project(pose.pos, pose.floor)
        } else {
            route.project(pose.pos, pose.floor, progress - cfg.windowBackM, progress + cfg.windowAheadM)
                ?: route.project(pose.pos, pose.floor)
        }
        started = true
        if (proj == null) {
            return GuidanceState(NavPhase.OFF_ROUTE, headline = "OFF ROUTE", nextAction = "You are on a different floor", remainingM = remaining, progressM = progress, floor = pose.floor)
        }
        // progress only moves forward (except a small tolerance), stops back-and-forth flicker
        progress = if (proj.s > progress || progress - proj.s > cfg.windowBackM - 0.1) proj.s else progress
        if (first) progress = proj.s
        val rem = route.length - progress

        // off-route detection with hold time
        val isOff = proj.lateral > cfg.offRouteM
        if (isOff) {
            if (offSince < 0) offSince = nowMs
        } else offSince = -1
        val offRoute = isOff && nowMs - offSince >= cfg.offRouteHoldMs

        // upcoming maneuver
        val nextM = route.maneuvers.firstOrNull { it.atS > progress + 0.3 } ?: route.maneuvers.last()
        val dM = (nextM.atS - progress).coerceAtLeast(0.0)

        // arrival
        val endPos = route.end.pos
        if (arrived || (rem <= cfg.arriveM && pose.pos.distanceTo(endPos) <= cfg.arriveM + 1.0)) {
            arrived = true
            val rel = Angles.norm(Angles.bearing(pose.pos, endPos) - pose.headingDeg)
            return GuidanceState(
                NavPhase.ARRIVED, null, rel, "YOU HAVE ARRIVED", "Point the camera at ${route.destinationName}",
                0.0, route.destinationName, 0.0, progress, proj.lateral, endPos, proj.point, nextM, 0.0, pose.floor
            )
        }

        // floor change: stand at the stairs / lift and wait for re-localisation upstairs
        if (nextM.kind == ManeuverKind.FLOOR_CHANGE && dM < 1.5) {
            val via = when (nextM.via) { EdgeKind.LIFT -> "lift"; EdgeKind.ESCALATOR -> "escalator"; else -> "stairs" }
            val up = (nextM.toFloor ?: 0) > pose.floor
            return GuidanceState(
                NavPhase.FLOOR_CHANGE, null, null, "TAKE THE ${via.uppercase()}",
                "Go ${if (up) "up" else "down"} to floor ${nextM.toFloor}, then scan a landmark",
                dM, nextM.placeName.ifBlank { null }, rem, progress, proj.lateral, null, proj.point, nextM, dM, pose.floor
            )
        }

        // look-ahead point along the route (never the final pin directly)
        val la = (cfg.lookAheadM).coerceAtMost(rem).coerceAtLeast(cfg.minLookAheadM.coerceAtMost(rem))
        // if we are off to the side, aim back to the route first
        val target = if (proj.lateral > 1.5 && proj.lateral < cfg.offRouteM * 3) route.pointAt(progress + la * 0.5) else route.pointAt(progress + la)
        val raw = Angles.norm(Angles.bearing(pose.pos, target) - pose.headingDeg)
        val sector = stabilizer.update(raw, nowMs, frozen = quality == TrackingQuality.UNSTABLE)

        val phase = when {
            quality == TrackingQuality.UNSTABLE -> NavPhase.UNSTABLE
            offRoute -> NavPhase.OFF_ROUTE
            else -> NavPhase.NAVIGATING
        }
        val nextText = describeNext(nextM, dM)
        return GuidanceState(
            phase, sector, stabilizer.smoothedDeg, sector.headline, nextText, dM,
            route.nextNamedAfter(progress), rem, progress, proj.lateral, target, proj.point, nextM, dM, pose.floor
        )
    }

    companion object {
        fun turnWord(turnDeg: Double): String {
            val a = abs(turnDeg)
            val side = if (turnDeg > 0) "right" else "left"
            return when {
                a < 45 -> "slight $side"
                a < 135 -> side
                else -> "sharp $side"
            }
        }

        fun metres(d: Double): String {
            val v = if (d < 10) (d * 2).roundToInt() / 2.0 else d.roundToInt().toDouble()
            return if (v % 1.0 == 0.0) "${v.toInt()} m" else "$v m"
        }

        fun describeNext(m: Maneuver, d: Double): String = when (m.kind) {
            ManeuverKind.TURN -> {
                val w = turnWord(m.turnDeg)
                val turn = if (w.startsWith("slight")) "Bear $w" else "Turn $w"
                val at = if (m.placeName.isNotBlank()) " at ${m.placeName}" else ""
                "$turn in ${metres(d)}$at"
            }
            ManeuverKind.FLOOR_CHANGE -> "Floor change in ${metres(d)}"
            ManeuverKind.ARRIVE -> "${m.placeName} in ${metres(d)}" + when {
                m.turnDeg > 30 -> ", on your right"
                m.turnDeg < -30 -> ", on your left"
                else -> ""
            }
        }
    }
}
