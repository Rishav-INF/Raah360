package com.raah360.core

/**
 * Aligns the AR session with the venue map using known landmarks and keeps that alignment
 * corrected as more landmarks are seen ("ARCore tracks movement between landmarks,
 * landmarks periodically correct the position").
 */
class Localizer(var venue: Venue) {

    data class Fix(val landmarkId: String, val arPos: Vec2, val mapPos: Vec2, val timeMs: Long)

    var transform: Transform2D? = null
        private set
    var floor: Int = venue.floors.firstOrNull { it.level == 1 }?.level ?: (venue.nodes.firstOrNull()?.floor ?: 0)
        private set
    var lastFix: Fix? = null
        private set
    var lastCorrectionM: Double = 0.0
        private set
    /** Metres walked since the last landmark fix (drift grows with this). */
    var metresSinceFix: Double = 0.0
        private set
    private var lastArPos: Vec2? = null
    private var prevFixOther: Fix? = null

    /** Max camera→landmark distance accepted for a fix (orientation gets noisy further away). */
    var maxFixDistanceM = 4.0
    /** If false, unlocated (survey-estimated) landmarks are ignored for alignment. */
    var trustUnlocated = true

    val isLocalized get() = transform != null

    fun reset() {
        transform = null; lastFix = null; metresSinceFix = 0.0; lastArPos = null; prevFixOther = null
    }

    /** Mapper: define the map frame from where the phone stands now. */
    fun setOriginHere(arPos: Vec2, arHeading: Double, mapPos: Vec2, mapHeading: Double, floor: Int) {
        transform = Transform2D.fromLandmark(mapPos, mapHeading, arPos, arHeading)
        this.floor = floor
        metresSinceFix = 0.0
    }

    /**
     * Feed a landmark observation. Returns true if it was used.
     * The first fix snaps; later fixes blend so the user's dot never jumps wildly.
     */
    fun onLandmark(id: String, arPos: Vec2, arYaw: Double, distanceM: Double, nowMs: Long): Boolean {
        val lm = venue.landmark(id) ?: return false
        if (!lm.located && !trustUnlocated) return false
        if (distanceM > maxFixDistanceM) return false
        val single = Transform2D.fromLandmark(lm.pos, lm.yawDeg, arPos, arYaw)
        var target = single

        // Two different landmarks seen recently → derive yaw from their positions,
        // which is more accurate than a small marker's own orientation.
        val other = (if (lastFix?.landmarkId != id) lastFix else prevFixOther)
            ?.takeIf { it.landmarkId != id && nowMs - it.timeMs < 90_000 }
        if (other != null) {
            val arVec = arPos - other.arPos
            val mapVec = lm.pos - other.mapPos
            if (arVec.length() > 4.0 && mapVec.length() > 4.0 && kotlin.math.abs(arVec.length() - mapVec.length()) < 2.5) {
                val yawPair = Angles.norm(mapVec.heading() - arVec.heading())
                // only accept when it agrees roughly with the marker's own orientation
                if (Angles.diff(yawPair, single.yawOffsetDeg) < 25.0) {
                    val yaw = Angles.norm(single.yawOffsetDeg + Angles.norm(yawPair - single.yawOffsetDeg) * 0.7)
                    target = Transform2D(yaw, lm.pos - arPos.rotateHeading(yaw))
                }
            }
        }

        val cur = transform
        transform = if (cur == null || lm.floor != floor) {
            target
        } else {
            val before = cur.apply(arPos)
            val after = target.apply(arPos)
            lastCorrectionM = before.distanceTo(after)
            // big disagreement right after a fix from the same landmark = noisy detection; be gentle
            val k = if (lastFix?.landmarkId == id && nowMs - (lastFix?.timeMs ?: 0) < 2000) 0.15 else 0.6
            cur.blend(target, k)
        }
        floor = lm.floor
        val fix = Fix(id, arPos, lm.pos, nowMs)
        if (lastFix?.landmarkId != id) prevFixOther = lastFix
        lastFix = fix
        metresSinceFix = 0.0
        return true
    }

    /** AR camera position/heading → map pose. Null until localised. */
    fun mapPose(arPos: Vec2, arHeading: Double): Pose? {
        val t = transform ?: return null
        lastArPos?.let { metresSinceFix += it.distanceTo(arPos) }
        lastArPos = arPos
        return Pose(t.apply(arPos), t.applyHeading(arHeading), floor)
    }

    /** Map position of something observed in AR (used by the Mapper to measure landmarks). */
    fun toMap(arPos: Vec2): Vec2? = transform?.apply(arPos)
    fun toMapHeading(h: Double): Double? = transform?.applyHeading(h)

    /** Replace the alignment (small corrections from corridors / compass). */
    fun override(t: Transform2D) { transform = t }

    /** Called when the user confirms a floor change (stairs/lift) before re-localising. */
    fun changeFloor(level: Int) {
        floor = level
        transform = null
    }
}
