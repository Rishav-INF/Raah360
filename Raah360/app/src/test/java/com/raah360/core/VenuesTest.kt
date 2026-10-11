package com.raah360.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VenuesTest {

    private fun bundled(): List<Venue> {
        val dir = listOf("src/main/assets/venues", "app/src/main/assets/venues").map { File(it) }.first { it.isDirectory }
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.map { VenueCodec.decode(it.readText()) }
    }

    @Test
    fun everyShippedMapIsNearbyAndFullyWalkable() {
        val home = GeoPoint(12.973611, 77.608889)
        val venues = bundled()
        assertEquals(5, venues.size)
        assertEquals(venues.size, venues.map { it.id }.toSet().size)
        for (v in venues) {
            assertTrue("${v.id} has no GPS position", v.lat != null && v.lng != null)
            val d = Geo.distance(home, GeoPoint(v.lat!!, v.lng!!))
            assertTrue("${v.id} is ${d.toInt()} m away", d < 3000)
            val planner = RoutePlanner(v)
            val from = v.pins.first()
            for (to in v.pins.drop(1)) {
                assertNotNull("${v.id}: no route ${from.name} -> ${to.name}", planner.planToPin(from.pos, from.floor, to))
            }
        }
        // the user's own building is the nearest
        assertEquals("atrium-blr", venues.minByOrNull { Geo.distance(home, GeoPoint(it.lat!!, it.lng!!)) }!!.id)
    }

    @Test
    fun atriumRoutesBetweenFloorsByStairsOrEscalator() {
        val v = bundled().first { it.id == "atrium-blr" }
        val a = v.pin("p_roomA")!!
        val d = v.pin("p0_d")!!
        assertEquals(1, a.floor); assertEquals(0, d.floor)
        val r = RoutePlanner(v).planToPin(a.pos, a.floor, d)!!
        val change = r.maneuvers.single { it.kind == ManeuverKind.FLOOR_CHANGE }
        assertEquals(0, change.toFloor)
        assertTrue(change.via == EdgeKind.STAIRS || change.via == EdgeKind.ESCALATOR)
        assertEquals(0, r.end.floor)
        val text = NavPhrases.floorChange(Lang.EN, change, 1)
        assertTrue(text, text == "Take the stairs down to Ground floor" || text == "Take the escalator down to Ground floor")
        // and every ground-floor place is reachable from the main entrance
        val entrance = v.pin("p0_entrance")!!
        for (p in v.pins) assertNotNull("no route to ${p.name} (${p.floor})", RoutePlanner(v).planToPin(entrance.pos, 0, p))
    }
}
