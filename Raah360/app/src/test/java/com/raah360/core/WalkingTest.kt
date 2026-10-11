package com.raah360.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WalkingTest {

    private fun loadSeed(): Venue {
        val candidates = listOf("src/main/assets/venues/default_venue.json", "app/src/main/assets/venues/default_venue.json")
        return VenueCodec.decode(candidates.map { File(it) }.first { it.exists() }.readText())
    }

    private fun routeAtoF(v: Venue) = RoutePlanner(v).planToPin(v.pin("p_roomA")!!.pos, 1, v.pin("p_roomF")!!)!!

    private fun heading(r: Route, w: StepWalker): Double {
        val s = r.project(w.pos, 1)!!.s
        return (r.pointAt(s + 1.0) - r.pointAt(s)).heading()
    }

    @Test
    fun walkingTheRouteNeverRaisesWrongWayAndArrives() {
        val v = loadSeed()
        val r = routeAtoF(v)
        val w = StepWalker(v, 1, r.pointAt(0.0))
        val mon = WrongWayMonitor(r)
        repeat(60) {
            val h = heading(r, w)
            val st = mon.update(w.step(h), 1, h)
            assertFalse("step $it went wrong at ${w.pos}", st.wrong)
        }
        assertTrue(w.pos.distanceTo(r.end.pos) < 1.5)
    }

    /** The wrong way round the loop from Room A: towards Room C2 past the B rooms, following the corridor. */
    private fun wrongRoute(v: Venue) = RoutePlanner(v).planToPin(v.pin("p_roomA")!!.pos, 1, v.pin("p_c2")!!)!!

    @Test
    fun walkingOppositeIsCaughtWithinAFewStepsAndDrawsATrail() {
        val v = loadSeed()
        val r = routeAtoF(v)
        val wrongWay = wrongRoute(v)
        val w = StepWalker(v, 1, r.pointAt(0.0))
        val mon = WrongWayMonitor(r)
        var caughtAt = -1
        var st: WrongWayMonitor.State? = null
        for (i in 1..12) {
            val h = heading(wrongWay, w)
            st = mon.update(w.step(h), 1, h)
            if (st.wrong && caughtAt < 0) caughtAt = i
        }
        assertTrue("caught at step $caughtAt", caughtAt in 1..4)
        assertTrue(st!!.trail.size >= 5)
        // the walker stayed on the corridors (walls and atrium are not walkable)
        assertTrue(v.snapToGraph(w.pos, 1)!!.distance < 0.05)
    }

    @Test
    fun turningBackClearsTheWarningAndLongDetoursAskForANewRoute() {
        val v = loadSeed()
        val r = routeAtoF(v)
        val w = StepWalker(v, 1, r.pointAt(0.0))
        val mon = WrongWayMonitor(r)
        val wrongWay = wrongRoute(v)
        repeat(4) { val h = heading(wrongWay, w); mon.update(w.step(h), 1, h) }
        var recovered = false
        repeat(12) {
            // walk back the way you came, then on along the route
            val h = if (w.pos.distanceTo(r.pointAt(0.0)) > 0.6) {
                val sw = wrongWay.project(w.pos, 1)!!.s
                (wrongWay.pointAt(sw - 1.0) - wrongWay.pointAt(sw)).heading()
            } else heading(r, w)
            val st = mon.update(w.step(h), 1, h)
            if (st.justRecovered) recovered = true
        }
        assertTrue(recovered)
        // now keep going the wrong way for a long time
        val w2 = StepWalker(v, 1, r.pointAt(0.0))
        val mon2 = WrongWayMonitor(r)
        var reroute = false
        repeat(30) { val h = heading(wrongWay, w2); if (mon2.update(w2.step(h), 1, h).shouldReroute) reroute = true }
        assertTrue(reroute)
    }

    @Test
    fun compassCalibrationAndRefinement() {
        val c = HeadingCalibrator(null)
        assertNull(c.toMap(10.0))
        // map +y points to compass 40°: facing map heading 90 reads compass 130
        c.calibrate(130.0, 90.0)
        assertEquals(40.0, c.northDeg!!, 1e-9)
        assertEquals(90.0, c.toMap(130.0)!!, 1e-9)
        // walking the route, the compass reads 5° more than expected: offset creeps that way
        repeat(100) { c.refine(135.0, 90.0) }
        assertEquals(45.0, c.northDeg!!, 0.1)
        // walking the opposite way must not "re-calibrate" it
        assertFalse(c.refine(315.0, 90.0))
        assertEquals(45.0, c.northDeg!!, 0.1)
        // from a GPS geo-reference
        assertEquals(-30.0, HeadingCalibrator.fromGeo(Transform2D(30.0, Vec2.ZERO))!!, 1e-9)
    }

    @Test
    fun barometerFindsTheFloorAndIgnoresNoise() {
        val est = FloorEstimator(startFloor = 1, floorHeightM = 3.6)
        val p0 = 1006.0
        val perMetre = 0.12 // hPa per metre near sea level
        repeat(50) { est.feed(p0 + if (it % 2 == 0) 0.05 else -0.05) }
        assertEquals(1, est.floor)
        // walk up one storey
        repeat(80) { est.feed(p0 - 3.6 * perMetre) }
        assertEquals(2, est.floor)
        // down two storeys to the ground floor
        repeat(120) { est.feed(p0 + 3.6 * perMetre) }
        assertEquals(0, est.floor)
        // a landing halfway up does not count as a floor
        repeat(120) { est.feed(p0 - 1.0 * perMetre) }
        assertEquals(1, est.floor)
    }

    @Test
    fun everyLanguageHasEveryPhraseWithTheSamePlaceholders() {
        val en = I18n.TABLES[Lang.EN]!!
        val ph = Regex("\\{(\\w+)\\}")
        for (lang in Lang.entries) {
            val t = I18n.TABLES[lang]!!
            for ((k, v) in en) {
                val s = t[k]
                assertNotNull("${lang.englishName} is missing '$k'", s)
                assertEquals("${lang.englishName} '$k' placeholders", ph.findAll(v).map { it.value }.toSet(), ph.findAll(s!!).map { it.value }.toSet())
            }
        }
        assertEquals("Turn right in 4 m at Room F corner",
            NavPhrases.next(Lang.EN, Maneuver(ManeuverKind.TURN, 10.0, 90.0, 1, "Room F corner"), 4.0))
        assertEquals("4 मी में Room F corner पर दाएँ मुड़ें",
            NavPhrases.next(Lang.HI, Maneuver(ManeuverKind.TURN, 10.0, 90.0, 1, "Room F corner"), 4.0))
    }

    @Test
    fun announcerWarnsOnceThenWaitsAndCelebratesRecovery() {
        val a = NavAnnouncer(Lang.EN, "Room F")
        val g = GuidanceState(NavPhase.NAVIGATING)
        val wrong = WrongWayMonitor.State(true, 0.0, 3.0, emptyList(), true, false, false)
        assertEquals("Wrong way. Turn around.", a.update(g, wrong, 0))
        assertNull(a.update(g, wrong.copy(justWentWrong = false), 3000))
        assertEquals("Wrong way. Turn around.", a.update(g, wrong.copy(justWentWrong = false), 7000))
        val ok = WrongWayMonitor.State(false, 1.0, 0.2, emptyList(), false, true, false)
        assertEquals("Back on track", a.update(g, ok, 8000))
    }

    /**
     * Compass readings taken on site (phone compass app, facing along the corridor), 11 Oct 2026.
     * With the venue's stored offset, every walk's first corridor heading must match within 10°.
     */
    @Test
    fun surveyedCompassBearingsMatchTheMap() {
        val v = loadSeed()
        val north = v.northDeg!!
        val nodes = { id: String -> v.node(id)!!.pos }
        // (from corner/node, towards node, measured compass bearing)
        val walks = listOf(
            Triple("a_roomA", "a_scalers", 279.0),   // A -> Room F along side A
            Triple("d_escalator", "c_c4", 279.0),    // escalator -> washroom along side C
            Triple("d_escalator", "d_booths", 195.0), // escalator -> A along side D
            Triple("cBC", "b_shafts", 195.0),        // washroom corner -> Room F along side B
            Triple("a_scalers", "a_roomA", 102.0),   // Scalers / F -> A along side A
            Triple("c_wash", "c_c1", 102.0),         // washroom -> escalator along side C
        )
        for ((a, b, measured) in walks) {
            val mapHeading = (nodes(b) - nodes(a)).heading()
            val predicted = Angles.norm(mapHeading + north)
            assertTrue("$a->$b predicted %.0f measured %.0f".format(predicted, measured), Angles.diff(predicted, measured) <= 10.0)
        }
        // and the calibrator turns a reading back into the corridor direction
        val c = HeadingCalibrator(north)
        assertEquals(90.0, c.toMap(279.0)!!, 6.0)
    }
}
