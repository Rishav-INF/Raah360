package com.raah360.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D point/vector on a floor plane of the venue map (metres).
 *
 * Map convention used everywhere in Raah360:
 *  - x grows to the right, y grows "up" on the plan.
 *  - A *heading* is in degrees, 0 = +y, clockwise positive (like a compass),
 *    so 90 = +x. Headings are map-relative, never magnetic north.
 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun length() = hypot(x, y)
    fun distanceTo(o: Vec2) = hypot(x - o.x, y - o.y)
    fun normalized(): Vec2 {
        val l = length()
        return if (l < 1e-9) ZERO else Vec2(x / l, y / l)
    }

    /** Heading (compass style, degrees) of this vector. */
    fun heading(): Double = Math.toDegrees(atan2(x, y))

    /** Rotate the vector clockwise by [deg] (i.e. adds [deg] to its heading). */
    fun rotateHeading(deg: Double): Vec2 {
        val r = Math.toRadians(deg)
        val c = cos(r)
        val s = sin(r)
        // clockwise rotation in a y-up frame
        return Vec2(c * x + s * y, -s * x + c * y)
    }

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun fromHeading(deg: Double, length: Double = 1.0): Vec2 {
            val r = Math.toRadians(deg)
            return Vec2(sin(r) * length, cos(r) * length)
        }
    }
}

object Angles {
    /** Normalise to (-180, 180]. */
    fun norm(deg: Double): Double {
        var a = deg % 360.0
        if (a <= -180.0) a += 360.0
        if (a > 180.0) a -= 360.0
        return a
    }

    /** Smallest absolute difference between two headings. */
    fun diff(a: Double, b: Double) = abs(norm(a - b))

    fun bearing(from: Vec2, to: Vec2): Double = (to - from).heading()
}

/** Result of projecting a point onto a segment. */
data class SegmentProjection(val point: Vec2, val t: Double, val distance: Double)

fun projectOnSegment(p: Vec2, a: Vec2, b: Vec2): SegmentProjection {
    val ab = b - a
    val len2 = ab.dot(ab)
    val t = if (len2 < 1e-12) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
    val q = a + ab * t
    return SegmentProjection(q, t, p.distanceTo(q))
}

/**
 * Rigid 2D transform that maps AR-session plane coordinates to venue map coordinates.
 * map = rotateHeading(arPoint, yawOffsetDeg) + translation
 */
data class Transform2D(val yawOffsetDeg: Double, val translation: Vec2) {
    fun apply(p: Vec2): Vec2 = p.rotateHeading(yawOffsetDeg) + translation
    fun applyHeading(h: Double): Double = Angles.norm(h + yawOffsetDeg)
    fun inverse(): Transform2D {
        val inv = -yawOffsetDeg
        return Transform2D(inv, (translation * -1.0).rotateHeading(inv))
    }

    /** Blend toward [target] (k=0 keep this, k=1 take target). Used for gentle drift correction. */
    fun blend(target: Transform2D, k: Double): Transform2D {
        val dYaw = Angles.norm(target.yawOffsetDeg - yawOffsetDeg)
        return Transform2D(
            Angles.norm(yawOffsetDeg + dYaw * k),
            translation + (target.translation - translation) * k
        )
    }

    companion object {
        val IDENTITY = Transform2D(0.0, Vec2.ZERO)

        /**
         * Build the AR→map transform from one observation of a known landmark.
         * @param mapPos landmark position on the map
         * @param mapYaw heading the landmark faces on the map
         * @param arPos landmark position in AR plane coordinates
         * @param arYaw heading the landmark faces in the AR plane frame
         */
        fun fromLandmark(mapPos: Vec2, mapYaw: Double, arPos: Vec2, arYaw: Double): Transform2D {
            val yaw = Angles.norm(mapYaw - arYaw)
            val t = mapPos - arPos.rotateHeading(yaw)
            return Transform2D(yaw, t)
        }
    }
}

/** Heading of a horizontal-ish 3D direction expressed in AR coordinates (x right, y up, z back). */
object ArFrame {
    /** Convert AR world (x, z) to the AR plane frame where -z is heading 0. */
    fun toPlane(x: Float, z: Float) = Vec2(x.toDouble(), -z.toDouble())

    /**
     * Heading of the user in AR plane frame, robust to how the phone is held:
     * sums the horizontal part of the camera's viewing direction (-Z) and of the
     * screen's "up" axis (+Y of display-oriented pose). Upright phone → view dir dominates,
     * flat phone → screen-top dominates. Returns null when neither is usable.
     */
    fun userHeading(zAxis: FloatArray, yAxis: FloatArray): Double? {
        val fx = -zAxis[0] + yAxis[0]
        val fz = -zAxis[2] + yAxis[2]
        val v = toPlane(fx, fz)
        if (v.length() < 0.25) return null
        return v.heading()
    }

    /**
     * Facing direction of a detected image landmark. For wall images the image normal (+Y)
     * is horizontal; for images lying flat we fall back to the image's top edge (-Z).
     */
    fun landmarkHeading(yAxis: FloatArray, zAxis: FloatArray): Double {
        val n = toPlane(yAxis[0], yAxis[2])
        if (n.length() > 0.5) return n.heading()
        return toPlane(-zAxis[0], -zAxis[2]).heading()
    }
}

internal fun sq(v: Double) = v * v
internal fun l2(values: DoubleArray): Double = sqrt(values.sumOf { it * it })
