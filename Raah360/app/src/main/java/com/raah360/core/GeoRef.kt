package com.raah360.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lng: Double)

object Geo {
    private const val EARTH_R = 6371008.8

    /** East/north metres of [p] relative to [o]. Flat-earth is fine at building scale. */
    fun enu(o: GeoPoint, p: GeoPoint): Vec2 {
        val x = Math.toRadians(p.lng - o.lng) * EARTH_R * cos(Math.toRadians((o.lat + p.lat) / 2))
        val y = Math.toRadians(p.lat - o.lat) * EARTH_R
        return Vec2(x, y)
    }

    fun distance(a: GeoPoint, b: GeoPoint) = enu(a, b).length()

    /**
     * Best rigid transform (rotation + translation, no scale: both sides are metres)
     * taking [src] onto [dst], weighted least squares (2D Procrustes).
     */
    fun fitRigid(src: List<Vec2>, dst: List<Vec2>, w: List<Double>): Transform2D {
        val sw = w.sum()
        var cs = Vec2.ZERO
        var cd = Vec2.ZERO
        for (i in src.indices) { cs += src[i] * (w[i] / sw); cd += dst[i] * (w[i] / sw) }
        var dot = 0.0
        var cross = 0.0
        for (i in src.indices) {
            val a = src[i] - cs
            val b = dst[i] - cd
            dot += w[i] * a.dot(b)
            cross += w[i] * (a.x * b.y - a.y * b.x)
        }
        // atan2 gives the counter-clockwise angle; Transform2D headings turn clockwise
        val yaw = -Math.toDegrees(atan2(cross, dot))
        return Transform2D(Angles.norm(yaw), cd - cs.rotateHeading(yaw))
    }
}

/** A place remembers the GPS fixes taken there: weighted mean, accuracy shrinks with each fix. */
fun VisualPin.withGeoFix(lat: Double, lng: Double, accuracyM: Double): VisualPin {
    val wNew = 1.0 / (accuracyM * accuracyM)
    val oldLat = this.lat
    val oldLng = this.lng
    val oldAcc = geoAccM
    if (oldLat == null || oldLng == null || oldAcc == null) return copy(lat = lat, lng = lng, geoAccM = accuracyM)
    val wOld = 1.0 / (oldAcc * oldAcc)
    val sw = wOld + wNew
    return copy(
        lat = (oldLat * wOld + lat * wNew) / sw,
        lng = (oldLng * wOld + lng * wNew) / sw,
        geoAccM = 1.0 / sqrt(sw),
    )
}

/**
 * GPS → venue map. Learnt from places where people said "I'm here" while having a GPS fix:
 * with two or more such places far enough apart, any GPS fix can be placed on the map;
 * with fewer, places are ranked by plain GPS distance to their own fixes.
 */
class GeoLocator(private val venue: Venue) {
    data class Suggestion(val pin: VisualPin, val distanceM: Double)

    private val fixed = venue.pins.filter { it.lat != null && it.lng != null }
    private val origin: GeoPoint? = fixed.firstOrNull()?.let { GeoPoint(it.lat!!, it.lng!!) }
        ?: if (venue.lat != null && venue.lng != null) GeoPoint(venue.lat, venue.lng) else null

    /** GPS (local east/north around [origin]) → map, or null until the venue is geo-referenced. */
    val transform: Transform2D? = run {
        val o = origin ?: return@run null
        if (fixed.size < 2) return@run null
        val spread = fixed.maxOf { a -> fixed.maxOf { b -> a.pos.distanceTo(b.pos) } }
        if (spread < MIN_SPREAD_M) return@run null
        Geo.fitRigid(
            fixed.map { Geo.enu(o, GeoPoint(it.lat!!, it.lng!!)) },
            fixed.map { it.pos },
            fixed.map { 1.0 / ((it.geoAccM ?: 20.0).let { a -> a * a }) },
        )
    }

    val knownPlaces: Int get() = fixed.size

    fun toMap(p: GeoPoint): Vec2? {
        val t = transform ?: return null
        return t.apply(Geo.enu(origin!!, p))
    }

    /** Places closest to a GPS fix, nearest first. Empty when the venue has no GPS knowledge yet. */
    fun suggest(p: GeoPoint, limit: Int = 3): List<Suggestion> {
        val onMap = toMap(p)
        val all = if (onMap != null) venue.pins.map { Suggestion(it, it.pos.distanceTo(onMap)) }
        else fixed.map { Suggestion(it, Geo.distance(GeoPoint(it.lat!!, it.lng!!), p)) }
        return all.sortedBy { it.distanceM }.take(limit)
    }

    /**
     * The suggestion to start from without asking, or null when GPS can't tell places apart.
     * Indoor GPS is often 10–30 m off, so this only fires on a clear, accurate winner.
     */
    fun confidentStart(p: GeoPoint, accuracyM: Double): Suggestion? {
        if (accuracyM > 20) return null
        val s = suggest(p, 2)
        val best = s.firstOrNull() ?: return null
        if (best.distanceM > maxOf(6.0, accuracyM)) return null
        val second = s.getOrNull(1)
        if (second != null && second.distanceM - best.distanceM < 3.0) return null
        return best
    }

    /** Metres to the building, when the venue knows where it is. */
    fun distanceToBuilding(p: GeoPoint): Double? = origin?.let { Geo.distance(it, p) }

    companion object {
        const val MIN_SPREAD_M = 8.0
    }
}
