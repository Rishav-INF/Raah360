package com.raah360.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

class CoreTest {

    private fun loadSeed(): Venue {
        val candidates = listOf("src/main/assets/venues/default_venue.json", "app/src/main/assets/venues/default_venue.json")
        val f = candidates.map { File(it) }.first { it.exists() }
        return VenueCodec.decode(f.readText())
    }

    @Test
    fun transformFromLandmarkMapsLandmarkOntoItself() {
        val t = Transform2D.fromLandmark(Vec2(10.0, 5.0), 90.0, Vec2(-2.0, 3.0), 200.0)
        val p = t.apply(Vec2(-2.0, 3.0))
        assertEquals(10.0, p.x, 1e-9); assertEquals(5.0, p.y, 1e-9)
        assertEquals(90.0, t.applyHeading(200.0), 1e-9)
        // a point 1 m in front of the landmark (AR) is 1 m in front of it on the map
        val front = t.apply(Vec2(-2.0, 3.0) + Vec2.fromHeading(200.0))
        assertEquals(11.0, front.x, 1e-9); assertEquals(5.0, front.y, 1e-9)
        val back = t.inverse().apply(p)
        assertEquals(-2.0, back.x, 1e-9); assertEquals(3.0, back.y, 1e-9)
    }

    @Test
    fun userHeadingFromCameraAxes() {
        // phone upright, camera looking along -z: heading 0
        assertEquals(0.0, ArFrame.userHeading(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f))!!, 1e-6)
        // looking along +x
        assertEquals(90.0, ArFrame.userHeading(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))!!, 1e-6)
        // phone flat, screen top pointing along -z, camera looking down
        assertEquals(0.0, ArFrame.userHeading(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, -1f))!!, 1e-6)
    }

    @Test
    fun sectorQuantisation() {
        assertEquals(Sector.FRONT, Sector.of(0.0))
        assertEquals(Sector.FRONT, Sector.of(22.0))
        assertEquals(Sector.FRONT_RIGHT, Sector.of(23.0))
        assertEquals(Sector.RIGHT, Sector.of(90.0))
        assertEquals(Sector.BACK, Sector.of(180.0))
        assertEquals(Sector.BACK, Sector.of(-170.0))
        assertEquals(Sector.LEFT, Sector.of(-80.0))
        assertEquals(Sector.FRONT_LEFT, Sector.of(-30.0))
    }

    @Test
    fun stabilizerIgnoresNoise() {
        val s = SectorStabilizer()
        var t = 0L
        val seen = HashSet<Sector>()
        for (a in listOf(42.0, 46.0, 43.0, 48.0, 50.0, 41.0, 44.0)) { t += 33; seen += s.update(a, t) }
        assertEquals(setOf(Sector.FRONT_RIGHT), seen)
        // wobbling around the 22.5° boundary must not flicker
        val s2 = SectorStabilizer()
        val seen2 = ArrayList<Sector>()
        t = 0
        for (i in 0 until 60) { t += 33; seen2 += s2.update(if (i % 2 == 0) 18.0 else 27.0, t) }
        assertTrue(seen2.distinct().size == 1)
    }

    @Test
    fun seedVenueIsConnectedAndRoundTrips() {
        val v = loadSeed()
        assertTrue(v.nodes.size > 15)
        assertTrue(VenueEditor.unreachableNodes(v).isEmpty())
        for (p in v.pins) assertNotNull("pin ${p.id} node", v.node(p.nodeId))
        val again = VenueCodec.decode(VenueCodec.encode(v))
        assertEquals(v.nodes, again.nodes)
        assertEquals(v.edges, again.edges)
        assertEquals(v.pins, again.pins)
        assertEquals(v.landmarks, again.landmarks)
    }

    @Test
    fun routeGoesAroundTheAtriumNotThroughIt() {
        val v = loadSeed()
        val start = v.node("b_roomF")!!.pos
        val pin = v.pin("p_bar")!!
        val r = RoutePlanner(v).planToPin(start, 1, pin)!!
        val straight = start.distanceTo(pin.pos)
        // corridor-centre geometry: about 27 m round vs 21 m across the void
        assertTrue("route ${r.length} vs straight $straight", r.length > straight * 1.25)
        // every route segment lies on a corridor edge (within 1 m of the loop polyline)
        val loop = v.edges.map { v.node(it.a)!!.pos to v.node(it.b)!!.pos }
        for (i in 0 until r.points.size - 1) {
            val mid = (r.points[i].pos + r.points[i + 1].pos) * 0.5
            val d = loop.minOf { projectOnSegment(mid, it.first, it.second).distance }
            assertTrue("segment $i off corridor by $d", d < 1.0)
        }
        // there are real turns on the way
        assertTrue(r.maneuvers.count { it.kind == ManeuverKind.TURN } >= 1)
    }

    /** Simulated walk with noisy pose: guidance must be stable, consistent with turns, and arrive. */
    @Test
    fun simulatedWalkIsStableAndArrives() {
        val v = loadSeed()
        val pin = v.pin("p_c2")!!
        val start = v.node("a_scalers")!!.pos
        val session = NavigationSession(v, pin)
        val rnd = Random(7)
        var t = 0L
        var pose = Pose(start, 90.0, 1)
        var frame = session.update(pose, TrackingQuality.GOOD, t)
        val route = frame.route!!
        var s = 0.0
        val sectors = ArrayList<Sector>()
        val speech = ArrayList<Pair<Long, String>>()
        var arrived = false
        while (t < 120_000) {
            t += 33
            s = (s + 1.2 * 0.033).coerceAtMost(route.length)
            val p = route.pointAt(s)
            val dir = route.pointAt((s + 0.6).coerceAtMost(route.length)) - p
            val h = if (dir.length() > 0.05) dir.heading() else pose.headingDeg
            pose = Pose(p + Vec2(rnd.nextDouble(-0.15, 0.15), rnd.nextDouble(-0.15, 0.15)), h + rnd.nextDouble(-8.0, 8.0), 1)
            frame = session.update(pose, TrackingQuality.GOOD, t)
            frame.speech?.let { speech += t to it.text }
            frame.guidance.sector?.let { sectors += it }
            if (frame.guidance.phase == NavPhase.ARRIVED) { arrived = true; break }
            assertTrue("never off-route on the ideal walk", frame.guidance.phase == NavPhase.NAVIGATING)
        }
        speech.forEach { println("    ${it.first / 1000.0}s  ${it.second}") }
        println("    route ${"%.1f".format(route.length)} m, maneuvers ${route.maneuvers.map { "${it.kind}@${"%.1f".format(it.atS)}(${"%.0f".format(it.turnDeg)})" }}")
        assertTrue("arrived", arrived)
        val switches = sectors.zipWithNext().count { it.first != it.second }
        val turns = route.maneuvers.count { it.kind == ManeuverKind.TURN }
        assertTrue("too many sector switches: $switches for $turns turns", switches <= 4 * turns + 4)
        assertTrue("mostly straight", sectors.count { it == Sector.FRONT } > sectors.size * 0.7)
        assertTrue(speech.any { it.second.contains("turn", ignoreCase = true) })
        assertTrue(speech.any { it.second.contains("arrived") })
        assertTrue("talks too much: ${speech.size}", speech.size <= 14)
        speech.zipWithNext().forEach { (a, b) -> assertTrue("repeated phrase", a.second != b.second || b.first - a.first > 5000) }
    }

    @Test
    fun walkingTheWrongWayTriggersReroute() {
        val v = loadSeed()
        val pin = v.pin("p_roomG")!!
        val session = NavigationSession(v, pin)
        var t = 0L
        val start = v.node("a_a3")!!.pos
        var f = session.update(Pose(start, 90.0, 1), TrackingQuality.GOOD, t)
        val r0 = f.route!!
        // walk in the opposite direction (towards Room A), past the corridor, off route
        var p = start
        var rerouted = false
        for (i in 0 until 400) {
            t += 33
            p = p + Vec2(-0.04, if (i > 150) 0.03 else 0.0)
            f = session.update(Pose(p, -90.0, 1), TrackingQuality.GOOD, t)
            if (f.routeChanged) rerouted = true
        }
        assertTrue("rerouted", rerouted || session.reroutes > 0 || f.guidance.sector == Sector.BACK)
        assertTrue(r0.length > 0)
    }

    @Test
    fun trackingLossNeverShowsADirection() {
        val v = loadSeed()
        val session = NavigationSession(v, v.pin("p_roomA")!!)
        session.update(Pose(v.node("c_c1")!!.pos, 0.0, 1), TrackingQuality.GOOD, 0)
        val f = session.update(null, TrackingQuality.LOST, 100)
        assertEquals(NavPhase.LOST, f.guidance.phase)
        assertEquals(null, f.guidance.sector)
    }

    @Test
    fun verifierSeparatesExactArrivalFromWrongSpot() {
        val rnd = Random(3)
        val w = 80; val h = 60
        val img = IntArray(w * h) { (0xff shl 24) or (rnd.nextInt(256) shl 16) or (rnd.nextInt(256) shl 8) or rnd.nextInt(256) }
        val sig = ImageSignature.compute(img, w, h)
        val pin = VisualPin("p", "Room X", "", 1, "n", Vec2(0.0, 0.0), 180.0, signature = sig)
        val near = ArrivalVerifier(pin)
        var st = near.update(Pose(Vec2(0.0, 1.0), 180.0, 1), sig, 0)
        st = near.update(Pose(Vec2(0.0, 1.0), 180.0, 1), sig, 1000)
        assertEquals(VerifyResult.EXACT_ARRIVAL, st.result)

        val far = ArrivalVerifier(pin)
        far.update(Pose(Vec2(6.0, 1.0), 180.0, 1), sig, 0)
        st = far.update(Pose(Vec2(6.0, 1.0), 180.0, 1), sig, 3000)
        assertEquals(VerifyResult.WRONG_SPOT, st.result)
        assertTrue(st.detail, st.detail.contains("6 m"))
    }

    @Test
    fun editorPinHereSplitsCorridor() {
        val v = loadSeed()
        val a = v.node("a_a3")!!.pos; val b = v.node("a_a4")!!.pos
        val mid = (a + b) * 0.5 + Vec2(0.0, -0.8)
        val (v2, pin) = VenueEditor.pinHere(v, "Coffee", mid, 1, 180.0)
        assertTrue(VenueEditor.unreachableNodes(v2).isEmpty())
        val r = RoutePlanner(v2).planToPin(v.node("a_roomA")!!.pos, 1, pin)!!
        assertTrue(abs(r.end.pos.x - mid.x) < 1e-6)
    }

    @Test
    fun localizerAlignsAndCorrectsDrift() {
        val v = loadSeed()
        val loc = Localizer(v)
        val m2 = v.landmark("M2")!!
        // AR session started somewhere arbitrary: AR frame is the map rotated by 37° and shifted
        val truth = Transform2D(37.0, Vec2(4.0, -2.0)).inverse() // map -> AR
        val arPos = truth.apply(m2.pos)
        val arYaw = Angles.norm(m2.yawDeg - 37.0)
        assertTrue(loc.onLandmark("M2", arPos, arYaw, 1.5, 0))
        // a point 5 m along side A in AR maps back to the right map point
        val mapP = Vec2(15.0, 0.0)
        val pose = loc.mapPose(truth.apply(mapP), Angles.norm(90.0 - 37.0))!!
        assertEquals(15.0, pose.pos.x, 1e-6); assertEquals(0.0, pose.pos.y, 1e-6)
        assertEquals(90.0, pose.headingDeg, 1e-6)
        // too far away -> ignored
        assertTrue(!loc.onLandmark("M2", arPos, arYaw, 9.0, 10))
        // second landmark seen with 3° yaw error in the marker itself: pair geometry pulls it back
        val m3 = v.landmark("M3")!!
        loc.onLandmark("M3", truth.apply(m3.pos), Angles.norm(m3.yawDeg - 37.0 + 3.0), 1.5, 20_000)
        val p2 = loc.mapPose(truth.apply(Vec2(2.0, 0.0)), 0.0)!!
        assertTrue("drift after pair fix ${p2.pos}", p2.pos.distanceTo(Vec2(2.0, 0.0)) < 0.6)
    }
}
