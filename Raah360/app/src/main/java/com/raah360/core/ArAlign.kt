package com.raah360.core

import kotlin.math.abs

/**
 * Lines ARCore's own coordinates up with the venue map, with no setup:
 *  - start: you stand at a known spot facing a known way (compass + the venue's offset),
 *  - while walking: ARCore tracks every centimetre; the map position is gently pulled back into
 *    the corridors, and the yaw slowly follows the compass so AR drift can't build up,
 *  - printed markers, where they exist, snap it exactly (same logic as the camera mode),
 *  - the camera's height tells the floor (3.6 m per storey), even without a barometer.
 */
class ArAligner(private val venue: Venue, private val floorHeightM: Double = venue.floorHeightM) {
    private val localizer = Localizer(venue)
    private var heights: HeightFloorTracker? = null
    /** Camera height (AR y) when aligned, and the floor you were on then. */
    var startY: Double = 0.0
        private set
    var startFloor: Int = 0
        private set

    val transform: Transform2D? get() = localizer.transform
    val aligned: Boolean get() = localizer.isLocalized

    fun reset() { localizer.reset(); heights = null }

    /** You are at [mapPos] on [floor] facing [mapHeading] (map degrees) while AR reads [arPos]/[arHeading]. */
    fun start(arPos: Vec2, arHeading: Double, camY: Double, mapPos: Vec2, mapHeading: Double, floor: Int) {
        localizer.setOriginHere(arPos, arHeading, mapPos, mapHeading, floor)
        startY = camY
        startFloor = floor
        heights = HeightFloorTracker(floor, floorHeightM)
    }

    fun pose(arPos: Vec2, arHeading: Double, floor: Int): Pose? =
        localizer.transform?.let { Pose(it.apply(arPos), it.applyHeading(arHeading), floor) }

    /**
     * Small per-frame corrections (call ~30×/s): pull back into the corridors when drifting out,
     * and turn the alignment a little towards the compass. Both are gentle so the view never jumps.
     */
    fun correct(arPos: Vec2, arHeading: Double, floor: Int, compassMapHeading: Double?, corridorPull: Double = 0.03, compassPull: Double = 0.01) {
        var t = localizer.transform ?: return
        val p = t.apply(arPos)
        venue.snapToGraph(p, floor)?.let { snap ->
            // corridors are ~2.4 m wide around the centre line: only pull when really outside
            if (snap.distance in CORRIDOR_HALF_M..LOST_M) t = Transform2D(t.yawOffsetDeg, t.translation + (snap.point - p) * corridorPull)
        }
        if (compassMapHeading != null) {
            val target = Angles.norm(compassMapHeading - arHeading)
            val d = Angles.norm(target - t.yawOffsetDeg)
            if (abs(d) < 45.0) {
                val yaw = Angles.norm(t.yawOffsetDeg + d * compassPull)
                val here = t.apply(arPos) // turn about where you stand, so your dot stays put
                t = Transform2D(yaw, here - arPos.rotateHeading(yaw))
            }
        }
        localizer.override(t)
    }

    /** A printed marker seen by ARCore: snaps the alignment (returns true if used). */
    fun onLandmark(id: String, arPos: Vec2, arYaw: Double, distanceM: Double, nowMs: Long): Boolean =
        localizer.onLandmark(id, arPos, arYaw, distanceM, nowMs)

    /** Floor from the camera height relative to where AR was aligned. */
    fun floorAt(camY: Double): Int? = heights?.feed(camY - startY)

    /** Where a map point is in AR plane coordinates (for drawing the route on the real floor). */
    fun toAr(mapPos: Vec2): Vec2? = localizer.transform?.inverse()?.apply(mapPos)
    fun toArHeading(mapHeading: Double): Double? = localizer.transform?.let { Angles.norm(mapHeading - it.yawOffsetDeg) }

    companion object {
        const val CORRIDOR_HALF_M = 1.3
        const val LOST_M = 5.0
    }
}

/** Floor from a height change (metres above where you started), with a band so landings don't count. */
class HeightFloorTracker(private val startFloor: Int, private val floorHeightM: Double = 3.6, private val bandM: Double = 1.0) {
    var floor: Int = startFloor
        private set

    fun feed(heightAboveStartM: Double): Int {
        val expected = (floor - startFloor) * floorHeightM
        val half = floorHeightM / 2 + bandM / 2
        if (heightAboveStartM > expected + half) floor++ else if (heightAboveStartM < expected - half) floor--
        return floor
    }
}
