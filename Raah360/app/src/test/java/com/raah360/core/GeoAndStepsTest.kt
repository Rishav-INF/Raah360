package com.raah360.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GeoAndStepsTest {

    private fun loadSeed(): Venue {
        val candidates = listOf("src/main/assets/venues/default_venue.json", "app/src/main/assets/venues/default_venue.json")
        return VenueCodec.decode(candidates.map { File(it) }.first { it.exists() }.readText())
    }

    private val origin = GeoPoint(12.9716, 77.5946)

    /** GPS position of map point [p] for a building whose map +y points [northDeg] clockwise from north. */
    private fun gpsOf(p: Vec2, northDeg: Double): GeoPoint {
        val enu = p.rotateHeading(northDeg)
        val lat = origin.lat + Math.toDegrees(enu.y / 6371008.8)
        val lng = origin.lng + Math.toDegrees(enu.x / (6371008.8 * cos(Math.toRadians(origin.lat))))
        return GeoPoint(lat, lng)
    }

    @Test
    fun fitRigidRecoversRotationAndTranslation() {
        val t = Transform2D(37.0, Vec2(4.0, -2.0))
        val src = listOf(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(3.0, 8.0))
        val dst = src.map { t.apply(it) }
        val fit = Geo.fitRigid(src, dst, listOf(1.0, 1.0, 1.0))
        assertEquals(37.0, fit.yawOffsetDeg, 1e-6)
        val p = fit.apply(Vec2(5.0, 5.0))
        val q = t.apply(Vec2(5.0, 5.0))
        assertEquals(q.x, p.x, 1e-6); assertEquals(q.y, p.y, 1e-6)
    }

    @Test
    fun geoFixesAverageAndTightenAccuracy() {
        val pin = loadSeed().pins.first()
        val a = pin.withGeoFix(10.0, 20.0, 20.0)
        assertEquals(20.0, a.geoAccM!!, 1e-9)
        val b = a.withGeoFix(10.001, 20.001, 20.0)
        assertEquals(10.0005, b.lat!!, 1e-9)
        assertTrue(b.geoAccM!! < 20.0)
        // round-trips through the venue file
        val v = loadSeed().let { it.copy(pins = it.pins.map { p -> if (p.id == pin.id) b else p }) }
        val back = VenueCodec.decode(VenueCodec.encode(v)).pin(pin.id)!!
        assertEquals(b.lat!!, back.lat!!, 1e-4); assertEquals(b.lng!!, back.lng!!, 1e-4)
    }

    @Test
    fun noGpsKnowledgeMeansNoGuess() {
        val geo = GeoLocator(loadSeed())
        assertEquals(0, geo.knownPlaces)
        assertTrue(geo.suggest(origin).isEmpty())
        assertNull(geo.confidentStart(origin, 5.0))
    }

    @Test
    fun twoLearntPlacesGeoreferenceTheWholeFloor() {
        val seed = loadSeed()
        val north = 120.0
        val learnt = setOf("p_roomA", "p_c2")
        val v = seed.copy(pins = seed.pins.map {
            if (it.id in learnt) gpsOf(it.pos, north).let { g -> it.withGeoFix(g.lat, g.lng, 8.0) } else it
        })
        val geo = GeoLocator(v)
        assertNotNull(geo.transform)
        // standing at Room F, which was never learnt: GPS still finds it
        val roomF = v.pin("p_roomF")!!
        val m = geo.toMap(gpsOf(roomF.pos, north))!!
        assertEquals(0.0, m.distanceTo(roomF.pos), 0.05)
        assertEquals("p_roomF", geo.suggest(gpsOf(roomF.pos, north)).first().pin.id)
        // ...but Room G is only 2 m away, so it won't pick one of them without asking
        assertNull(geo.confidentStart(gpsOf(roomF.pos, north), 6.0))
        // GPS can't tell storeys apart: ground-floor Room G is right below Room C2, so no guess there
        assertNull(geo.confidentStart(gpsOf(v.pin("p_c2")!!.pos, north), 6.0))
        // The Scalers has nothing close on either floor
        val scalers = v.pin("p_scalers")!!
        assertEquals("p_scalers", geo.confidentStart(gpsOf(scalers.pos, north), 6.0)?.pin?.id)
        // an inaccurate fix is never trusted on its own
        assertNull(geo.confidentStart(gpsOf(scalers.pos, north), 35.0))
    }

    @Test
    fun stepDetectorCountsWalkingSteps() {
        val d = StepPeakDetector()
        var steps = 0
        val hz = 50
        // 20 s of walking at 1.8 steps/s, ±2.5 m/s² bounce, then 5 s standing still with sensor noise
        for (i in 0 until hz * 25) {
            val t = i.toDouble() / hz
            val a = if (t < 20) 2.5 * sin(2 * PI * 1.8 * t) else 0.1 * sin(2 * PI * 7 * t)
            if (d.feed(a, (t * 1e9).toLong())) steps++
        }
        assertEquals(36.0, steps.toDouble(), 2.0)
    }

    @Test
    fun seedRoutesRunDownTheCorridorNotAlongTheGlass() {
        val v = loadSeed()
        // side A glass is y = 0 with the rooms on +y; the corridor is 2.4 m wide: centre line at −1.2 m
        assertEquals(-1.2, v.node("a_roomA")!!.pos.y, 1e-6)
        // place points stand in the corridor, in front of the door, not inside the room
        assertTrue(v.pin("p_roomA")!!.pos.y < 0.0)
        val r = RoutePlanner(v).planToPin(v.pin("p_roomA")!!.pos, 1, v.pin("p_roomF")!!)!!
        assertTrue(r.length in 20.0..24.0)
    }

    @Test
    fun seedFloorIsNotMirrored() {
        val v = loadSeed()
        // walking from Room A toward Room F along side A, side B (Room F) is ahead and turns RIGHT,
        // with the A rooms on your left — as on the real floor
        val r = RoutePlanner(v).planToPin(v.pin("p_roomA")!!.pos, 1, v.pin("p_roomF")!!)!!
        val turn = r.maneuvers.first { it.kind == ManeuverKind.TURN }
        assertTrue("turn ${turn.turnDeg}", turn.turnDeg > 45)
        val roomA = v.pin("p_roomA")!!
        val rel = Angles.norm(Angles.bearing(roomA.pos, roomA.pos + Vec2.fromHeading(roomA.facingDeg!!)) - 90.0)
        assertTrue("Room A is on the left of someone walking +x", rel < 0)
    }
}
