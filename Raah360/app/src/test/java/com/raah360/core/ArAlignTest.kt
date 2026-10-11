package com.raah360.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArAlignTest {

    private fun atrium(): Venue {
        val candidates = listOf("src/main/assets/venues/default_venue.json", "app/src/main/assets/venues/default_venue.json")
        return VenueCodec.decode(candidates.map { File(it) }.first { it.exists() }.readText())
    }

    /** ARCore's own frame is arbitrary: here it is rotated 37° and shifted from the map. */
    private val arFromMap = Transform2D(37.0, Vec2(5.0, -3.0))
    private fun ar(map: Vec2) = arFromMap.apply(map)
    private fun arHeading(mapHeading: Double) = arFromMap.applyHeading(mapHeading)

    @Test
    fun startPointAndCompassLineUpArWithTheMap() {
        val v = atrium()
        val r = RoutePlanner(v).planToPin(v.pin("p_roomA")!!.pos, 1, v.pin("p_roomF")!!)!!
        val a = ArAligner(v)
        val start = r.pointAt(0.0)
        a.start(ar(start), arHeading(90.0), 1.4, start, 90.0, 1)
        // walk 15 m along the route: AR reports it in its own frame, the aligner puts it back on the map
        val there = r.pointAt(15.0)
        val p = a.pose(ar(there), arHeading(90.0), 1)!!
        assertEquals(0.0, p.pos.distanceTo(there), 1e-6)
        assertEquals(90.0, p.headingDeg, 1e-6)
        // and back: map → AR, for drawing chevrons on the real floor
        assertEquals(0.0, a.toAr(there)!!.distanceTo(ar(there)), 1e-6)
        assertEquals(arHeading(90.0), a.toArHeading(90.0)!!, 1e-6)
    }

    @Test
    fun aCompassErrorAtTheStartIsCorrectedWhileWalking() {
        val v = atrium()
        val r = RoutePlanner(v).planToPin(v.pin("p_roomA")!!.pos, 1, v.pin("p_roomF")!!)!!
        val a = ArAligner(v)
        val start = r.pointAt(0.0)
        // the compass was 12° off when AR started
        a.start(ar(start), arHeading(90.0), 1.4, start, 102.0, 1)
        var s = 0.0
        var worst = 0.0
        while (s < 20.0) {
            s += 0.05
            val truth = r.pointAt(s)
            // now the compass reads true (±0): the yaw creeps back; corridors pull the position in
            a.correct(ar(truth), arHeading(90.0), 1, 90.0)
            worst = maxOf(worst, a.pose(ar(truth), arHeading(90.0), 1)!!.pos.distanceTo(truth))
        }
        val end = r.pointAt(20.0)
        val p = a.pose(ar(end), arHeading(90.0), 1)!!
        // what's left is sideways error inside the 2.4 m corridor, which corridors don't (and shouldn't) pull
        assertTrue("ended ${p.pos.distanceTo(end)} m off", p.pos.distanceTo(end) < ArAligner.CORRIDOR_HALF_M)
        assertEquals(90.0, p.headingDeg, 2.0)
        // without corrections a 12° error over 20 m would be ~4 m sideways — into the rooms
        assertTrue("worst $worst m", worst < 2.5)
    }

    @Test
    fun drifting_outside_the_corridor_is_pulled_back() {
        val v = atrium()
        val a = ArAligner(v)
        val start = v.node("a_roomA")!!.pos
        a.start(ar(start), arHeading(90.0), 1.4, start + Vec2(0.0, 3.0), 90.0, 1) // started 3 m into the rooms
        repeat(200) { a.correct(ar(start), arHeading(90.0), 1, null) }
        val p = a.pose(ar(start), arHeading(90.0), 1)!!
        assertTrue("still ${v.snapToGraph(p.pos, 1)!!.distance} m outside", v.snapToGraph(p.pos, 1)!!.distance <= ArAligner.CORRIDOR_HALF_M + 0.05)
    }

    @Test
    fun theCameraHeightTellsTheFloor() {
        val a = ArAligner(atrium())
        a.start(Vec2.ZERO, 0.0, 1.4, Vec2.ZERO, 0.0, 1)
        assertEquals(1, a.floorAt(1.5))
        assertEquals(1, a.floorAt(0.0))          // a landing halfway down is not a floor
        assertEquals(0, a.floorAt(1.4 - 3.6))    // down the escalator
        assertEquals(1, a.floorAt(1.4))          // and back up
        a.reset()
        assertFalse(a.aligned)
    }
}
