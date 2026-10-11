package com.raah360.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pedestrian dead reckoning: every step moves you [strideM] in the direction you are walking,
 * then snaps you back onto the walkable corridors (you can't walk through a wall or the atrium).
 */
class StepWalker(private val venue: Venue, var floor: Int, start: Vec2, val strideM: Double = 0.7) {
    var pos: Vec2 = snap(start)
        private set

    private fun snap(p: Vec2): Vec2 = venue.snapToGraph(p, floor)?.takeIf { it.distance < 3.0 }?.point ?: p

    /** One step towards [mapHeadingDeg]; returns the new position. */
    fun step(mapHeadingDeg: Double): Vec2 {
        pos = snap(pos + Vec2.fromHeading(mapHeadingDeg, strideM))
        return pos
    }

    /** "I'm here": the visitor corrected their position by tapping the map. */
    fun place(p: Vec2) { pos = snap(p) }
}

/**
 * Compass → map heading. The map is not drawn north-up, so the venue stores where map +y points
 * ([northDeg], compass bearing of map +y). Learnt once by a one-tap calibration, or from the GPS
 * geo-reference; refined gently while the visitor is clearly walking along the route.
 */
class HeadingCalibrator(var northDeg: Double?) {
    val calibrated: Boolean get() = northDeg != null

    fun toMap(compassDeg: Double): Double? = northDeg?.let { Angles.norm(compassDeg - it) }

    /** The visitor says "I'm facing [mapHeadingDeg]" while the compass reads [compassDeg]. */
    fun calibrate(compassDeg: Double, mapHeadingDeg: Double) { northDeg = Angles.norm(compassDeg - mapHeadingDeg) }

    /**
     * Nudges the offset towards what walking along [routeHeadingDeg] implies, but only when the two
     * already agree within [agreeDeg] (so a visitor going the wrong way never "re-calibrates" it).
     */
    fun refine(compassDeg: Double, routeHeadingDeg: Double, k: Double = 0.05, agreeDeg: Double = 30.0): Boolean {
        val n = northDeg ?: return false
        val implied = Angles.norm(compassDeg - routeHeadingDeg)
        val d = Angles.norm(implied - n)
        if (abs(d) > agreeDeg) return false
        northDeg = Angles.norm(n + d * k)
        return true
    }

    companion object {
        /** Offset from the GPS geo-reference: map = enu.rotateHeading(yaw) ⇒ map heading = true heading + yaw. */
        fun fromGeo(transform: Transform2D?): Double? = transform?.let { Angles.norm(-it.yawOffsetDeg) }
    }
}

/**
 * Notices when the visitor walks away from the route: off the route sideways, back along it,
 * or several steps in a row facing against it. Keeps the path walked while wrong (drawn red).
 */
class WrongWayMonitor(route: Route, private val cfg: Config = Config()) {
    data class Config(
        val offRouteM: Double = 2.5,
        val backtrackM: Double = 2.0,
        val oppositeDeg: Double = 110.0,
        val oppositeSteps: Int = 3,
        val clearLateralM: Double = 1.2,
        val rerouteAfterM: Double = 12.0,
    )

    data class State(
        val wrong: Boolean,
        val progressM: Double,
        val lateralM: Double,
        val trail: List<Vec2>,
        val justWentWrong: Boolean,
        val justRecovered: Boolean,
        val shouldReroute: Boolean,
    )

    var route: Route = route
        private set
    private var best = 0.0
    private var wrong = false
    private var opposite = 0
    private val trail = ArrayList<Vec2>()
    private var wrongDistM = 0.0
    private var last: Vec2? = null

    fun reset(newRoute: Route) {
        route = newRoute; best = 0.0; wrong = false; opposite = 0; trail.clear(); wrongDistM = 0.0; last = null
    }

    /** Call after every step (or position correction). [stepHeadingDeg] = direction of that step, if known. */
    fun update(pos: Vec2, floor: Int, stepHeadingDeg: Double? = null): State {
        val pr = route.project(pos, floor)
        val prev = last
        last = pos
        if (pr == null) return State(wrong, best, 0.0, trail.toList(), false, false, false)

        // facing against the route for a few steps in a row
        if (stepHeadingDeg != null) {
            val along = (route.pointAt(pr.s + 0.5) - route.pointAt(pr.s - 0.5)).heading()
            opposite = if (Angles.diff(stepHeadingDeg, along) > cfg.oppositeDeg) opposite + 1 else 0
        }
        val bad = pr.lateral > cfg.offRouteM || best - pr.s > cfg.backtrackM || opposite >= cfg.oppositeSteps
        val good = pr.lateral < cfg.clearLateralM && pr.s >= best - cfg.clearLateralM && opposite == 0
        if (!wrong && !bad && pr.lateral < cfg.offRouteM) best = maxOf(best, pr.s)

        var entered = false
        var recovered = false
        if (!wrong && bad) {
            wrong = true; entered = true; wrongDistM = 0.0
            trail.clear(); prev?.let { trail += it }; trail += pos
        } else if (wrong && good) {
            wrong = false; recovered = true; trail.clear(); best = maxOf(best, pr.s)
        } else if (wrong) {
            val moved = prev?.distanceTo(pos) ?: 0.0
            wrongDistM += moved
            if (trail.isEmpty() || trail.last().distanceTo(pos) > 0.2) trail += pos
        }
        return State(wrong, best, pr.lateral, trail.toList(), entered, recovered, wrong && wrongDistM >= cfg.rerouteAfterM)
    }
}

/**
 * Which floor you are on, from the barometer: air pressure drops about 0.12 hPa per metre up,
 * so one storey (~3.6 m) is ~0.43 hPa — easy to see. Measured relative to the floor you started on,
 * which cancels the weather; a hysteresis band stops it flickering on a landing.
 */
class FloorEstimator(private val startFloor: Int, private val floorHeightM: Double = 3.6, private val bandM: Double = 1.0) {
    private var p0: Double? = null
    private var filtered: Double? = null
    var floor: Int = startFloor
        private set
    /** Metres above where you started. */
    var heightM: Double = 0.0
        private set

    fun feed(hPa: Double): Int {
        val f = filtered?.let { it + (hPa - it) * 0.1 } ?: hPa
        filtered = f
        val base = p0 ?: f.also { p0 = it }
        heightM = altitude(f) - altitude(base)
        val expected = (floor - startFloor) * floorHeightM
        val half = floorHeightM / 2 + bandM / 2
        if (heightM > expected + half) floor++ else if (heightM < expected - half) floor--
        return floor
    }

    /** Re-anchor: you are on [level] now (e.g. confirmed at a landmark). */
    fun rebase(level: Int) {
        p0 = filtered?.let { pressureAt(altitude(it) - (level - startFloor) * floorHeightM) }
        floor = level
    }

    companion object {
        fun altitude(hPa: Double) = 44330.0 * (1 - (hPa / 1013.25).pow(1 / 5.255))
        private fun pressureAt(alt: Double) = 1013.25 * (1 - alt / 44330.0).pow(5.255)
    }
}

/** Circular smoothing for compass readings (no jump at 359° → 0°). */
class HeadingSmoother(private val k: Double = 0.2) {
    private var sx = 0.0
    private var sy = 0.0
    private var has = false

    fun feed(deg: Double): Double {
        val r = Math.toRadians(deg)
        if (!has) { sx = sin(r); sy = cos(r); has = true } else { sx += (sin(r) - sx) * k; sy += (cos(r) - sy) * k }
        return Math.toDegrees(atan2(sx, sy))
    }
}

/** Visitor-facing guidance phrases, in the chosen language. */
object NavPhrases {
    fun metres(lang: Lang, d: Double): String = I18n.t(lang, "meters", "n" to d.roundToInt().coerceAtLeast(1))

    fun metresVoice(lang: Lang, d: Double): String {
        val n = d.roundToInt().coerceAtLeast(1)
        return if (n == 1) I18n.t(lang, "metre_voice") else I18n.t(lang, "metres_voice", "n" to n)
    }

    fun dir(lang: Lang, turnDeg: Double): String {
        val a = abs(turnDeg)
        val side = if (turnDeg > 0) "right" else "left"
        val key = when {
            a < 45 -> "dir_slight_$side"
            a < 135 -> "dir_$side"
            else -> "dir_sharp_$side"
        }
        return I18n.t(lang, key)
    }

    /** "Take the escalator down to Ground floor" — from [fromFloor] to the maneuver's floor. */
    fun floorChange(lang: Lang, m: Maneuver, fromFloor: Int): String {
        val to = m.toFloor ?: fromFloor
        val via = I18n.t(lang, when (m.via) { EdgeKind.ESCALATOR -> "via_escalator"; EdgeKind.LIFT -> "via_lift"; else -> "via_stairs" })
        return I18n.t(lang, if (to > fromFloor) "take_up" else "take_down", "via" to via, "f" to floorName(lang, to))
    }

    /** The banner line: "Turn right in 4 m at Room F corner". [voice] spells distances out. */
    fun next(lang: Lang, m: Maneuver, d: Double, voice: Boolean = false, fromFloor: Int? = null): String {
        val dist = if (voice) metresVoice(lang, d) else metres(lang, d)
        return when (m.kind) {
            ManeuverKind.TURN -> if (m.placeName.isNotBlank())
                I18n.t(lang, "turn_in_at", "dir" to dir(lang, m.turnDeg), "d" to dist, "p" to m.placeName)
            else I18n.t(lang, "turn_in", "dir" to dir(lang, m.turnDeg), "d" to dist)
            ManeuverKind.FLOOR_CHANGE -> if (fromFloor != null) floorChange(lang, m, fromFloor) else I18n.t(lang, "floor_change_in", "d" to dist)
            ManeuverKind.ARRIVE -> when {
                m.turnDeg > 30 -> I18n.t(lang, "arrive_in_right", "p" to m.placeName, "d" to dist)
                m.turnDeg < -30 -> I18n.t(lang, "arrive_in_left", "p" to m.placeName, "d" to dist)
                else -> I18n.t(lang, "arrive_in", "p" to m.placeName, "d" to dist)
            }
        }
    }

    fun floorName(lang: Lang, level: Int) = if (level == 0) I18n.t(lang, "floor_ground") else I18n.t(lang, "floor_n", "n" to level)
}

/**
 * Decides what to SAY on the 3D map, in the chosen language: turns (soon / now), the destination
 * coming up, arrival, going the wrong way and getting back on track. Quiet otherwise.
 */
class NavAnnouncer(private val lang: Lang, private val destination: String, private val minGapMs: Long = 2500) {
    private val said = HashSet<String>()
    private var lastMs = -100000L
    private var lastWrongMs = -100000L
    private var lastStraightMs = -100000L

    private fun say(text: String, now: Long): String { lastMs = now; return text }

    fun update(g: GuidanceState, wrong: WrongWayMonitor.State?, now: Long): String? {
        val gap = now - lastMs >= minGapMs
        if (g.phase == NavPhase.ARRIVED) {
            return if (said.add("arrived")) say(I18n.t(lang, "v_arrived", "p" to destination), now) else null
        }
        if (wrong != null) {
            if (wrong.justRecovered) return say(I18n.t(lang, "back_on_track"), now)
            if (wrong.wrong) {
                return if (now - lastWrongMs > 6000) { lastWrongMs = now; say(I18n.t(lang, "v_wrong"), now) } else null
            }
        }
        val m = g.nextManeuver ?: return null
        val d = g.distToManeuverM ?: return null
        val id = "${m.kind}-${"%.1f".format(m.atS)}"
        when (m.kind) {
            ManeuverKind.TURN -> {
                val dirWord = dir(m.turnDeg)
                if (d <= 2.5 && said.add("now-$id")) {
                    said += "soon-$id"
                    return say(if (m.placeName.isNotBlank()) I18n.t(lang, "turn_now_at", "dir" to dirWord, "p" to m.placeName)
                    else I18n.t(lang, "turn_now", "dir" to dirWord), now)
                }
                if (d in 2.5..8.0 && gap && said.add("soon-$id")) return say(NavPhrases.next(lang, m, d, voice = true), now)
            }
            ManeuverKind.ARRIVE -> if (d <= 6.0 && gap && said.add("near")) {
                return say(when {
                    m.turnDeg > 30 -> I18n.t(lang, "v_near_right", "p" to m.placeName)
                    m.turnDeg < -30 -> I18n.t(lang, "v_near_left", "p" to m.placeName)
                    else -> I18n.t(lang, "v_near")
                }, now)
            }
            ManeuverKind.FLOOR_CHANGE -> if (d <= 8.0 && gap && said.add("floor-$id")) return say(NavPhrases.next(lang, m, d, voice = true, fromFloor = g.floor), now)
        }
        if (d > 10 && gap && now - lastStraightMs > 20000 && now - lastMs > 15000) {
            lastStraightMs = now
            return say(I18n.t(lang, "v_straight", "d" to NavPhrases.metresVoice(lang, d)), now)
        }
        return null
    }

    private fun dir(turnDeg: Double) = NavPhrases.dir(lang, turnDeg)
}
