package com.raah360.core

import java.util.PriorityQueue
import kotlin.math.abs

data class RoutePoint(
    val pos: Vec2,
    val floor: Int,
    val nodeId: String? = null,
    val name: String = "",
    val type: NodeType = NodeType.WAYPOINT,
)

enum class ManeuverKind { TURN, FLOOR_CHANGE, ARRIVE }

data class Maneuver(
    val kind: ManeuverKind,
    /** Distance along the route where the maneuver happens. */
    val atS: Double,
    /** Signed turn angle, + = right, - = left (TURN only). */
    val turnDeg: Double = 0.0,
    val pointIndex: Int,
    val placeName: String = "",
    val toFloor: Int? = null,
    val via: EdgeKind? = null,
) {
    val isRight get() = turnDeg > 0
}

data class RouteProjection(val s: Double, val point: Vec2, val lateral: Double, val segment: Int)

/** An ACTIVE ROUTE: polyline through walkable graph nodes, possibly spanning floors. */
class Route(val points: List<RoutePoint>, val destinationName: String, val transitionKinds: Map<Int, EdgeKind> = emptyMap()) {
    /** cum[i] = distance from start to points[i]; floor transitions add 0. */
    val cum: DoubleArray = DoubleArray(points.size)
    val maneuvers: List<Maneuver>

    init {
        require(points.isNotEmpty())
        for (i in 1 until points.size) {
            val seg = if (points[i].floor != points[i - 1].floor) 0.0 else points[i].pos.distanceTo(points[i - 1].pos)
            cum[i] = cum[i - 1] + seg
        }
        maneuvers = buildManeuvers()
    }

    val length: Double get() = cum.last()
    val end: RoutePoint get() = points.last()

    private fun isTransition(i: Int) = i + 1 < points.size && points[i].floor != points[i + 1].floor

    fun segmentDir(i: Int): Vec2 = (points[i + 1].pos - points[i].pos).normalized()

    private fun buildManeuvers(): List<Maneuver> {
        val out = mutableListOf<Maneuver>()
        var arriveSide = 0.0
        var i = 1
        while (i < points.size - 1) {
            if (isTransition(i)) {
                out += Maneuver(
                    ManeuverKind.FLOOR_CHANGE, cum[i], pointIndex = i, placeName = points[i].name,
                    toFloor = points[i + 1].floor, via = transitionKinds[i] ?: EdgeKind.STAIRS
                )
                i++
                continue
            }
            // incoming direction: last non-zero segment before i (same floor)
            val inDir = dirBefore(i)
            val outDir = dirAfter(i)
            if (inDir != null && outDir != null) {
                val turn = Angles.norm(outDir.heading() - inDir.heading())
                if (abs(turn) >= 30.0) {
                    if (i == points.size - 2 && length - cum[i] < 2.0) {
                        // last tiny leg into the door: "X is on your left" instead of a turn
                        arriveSide = turn
                    } else {
                        out += Maneuver(ManeuverKind.TURN, cum[i], turn, i, nameNear(i))
                    }
                }
            }
            i++
        }
        out += Maneuver(ManeuverKind.ARRIVE, length, arriveSide, pointIndex = points.size - 1, placeName = destinationName)
        return out
    }

    private fun dirBefore(i: Int): Vec2? {
        var j = i
        while (j > 0) {
            if (points[j - 1].floor == points[i].floor && points[j].pos.distanceTo(points[j - 1].pos) > 0.05)
                return (points[j].pos - points[j - 1].pos).normalized()
            if (points[j - 1].floor != points[i].floor) return null
            j--
        }
        return null
    }

    private fun dirAfter(i: Int): Vec2? {
        var j = i
        while (j < points.size - 1) {
            if (points[j + 1].floor != points[i].floor) return null
            if (points[j + 1].pos.distanceTo(points[j].pos) > 0.05)
                return (points[j + 1].pos - points[j].pos).normalized()
            j++
        }
        return null
    }

    private fun nameNear(i: Int): String = points[i].name

    /** Point at arc-length [s] (clamped). Uses the floor of the segment that contains s. */
    fun pointAt(s: Double): Vec2 {
        val sc = s.coerceIn(0.0, length)
        for (i in 0 until points.size - 1) {
            if (isTransition(i)) continue
            if (sc <= cum[i + 1] + 1e-9) {
                val segLen = cum[i + 1] - cum[i]
                if (segLen < 1e-9) return points[i + 1].pos
                val t = (sc - cum[i]) / segLen
                return points[i].pos + (points[i + 1].pos - points[i].pos) * t
            }
        }
        return points.last().pos
    }

    /** Floor that applies at arc-length [s]. */
    fun floorAt(s: Double): Int {
        for (i in 0 until points.size - 1) {
            if (s < cum[i + 1] - 1e-9 && !isTransition(i)) return points[i].floor
        }
        return points.last().floor
    }

    /**
     * Project a position onto the route, considering only segments on [floor]
     * whose arc-length range intersects [sMin, sMax] (keeps the user from "jumping"
     * to a parallel corridor further along the route).
     */
    fun project(p: Vec2, floor: Int, sMin: Double = 0.0, sMax: Double = Double.MAX_VALUE): RouteProjection? {
        var best: RouteProjection? = null
        for (i in 0 until points.size - 1) {
            if (isTransition(i)) continue
            if (points[i].floor != floor) continue
            if (cum[i + 1] < sMin || cum[i] > sMax) continue
            val pr = projectOnSegment(p, points[i].pos, points[i + 1].pos)
            var s = cum[i] + (cum[i + 1] - cum[i]) * pr.t
            var q = pr.point
            // clamp into window so we never move outside it
            if (s < sMin || s > sMax) {
                s = s.coerceIn(sMin, sMax)
                q = pointAt(s)
            }
            val d = p.distanceTo(q)
            if (best == null || d < best.lateral - 1e-9) best = RouteProjection(s, q, d, i)
        }
        if (best == null && points.size == 1 && points[0].floor == floor) {
            return RouteProjection(0.0, points[0].pos, p.distanceTo(points[0].pos), 0)
        }
        return best
    }

    /** Next named place strictly ahead of [s] (for "Toward X"). */
    fun nextNamedAfter(s: Double): String? {
        for (i in points.indices) {
            if (cum[i] > s + 1.0 && points[i].name.isNotBlank()) return points[i].name
        }
        return null
    }
}

class RoutePlanner(private val venue: Venue) {

    private data class Arc(val to: String, val cost: Double, val kind: EdgeKind)

    private fun edgeCost(a: MapNode, b: MapNode, kind: EdgeKind): Double {
        val d = a.pos.distanceTo(b.pos)
        return when (kind) {
            EdgeKind.WALK -> d
            EdgeKind.STAIRS -> d + 12.0
            EdgeKind.ESCALATOR -> d + 8.0
            EdgeKind.LIFT -> d + 25.0
        }
    }

    private fun adjacency(extra: List<Triple<String, String, Double>>): Map<String, List<Arc>> {
        val adj = HashMap<String, MutableList<Arc>>()
        for (e in venue.edges) {
            val a = venue.node(e.a) ?: continue
            val b = venue.node(e.b) ?: continue
            val c = edgeCost(a, b, e.kind)
            adj.getOrPut(a.id) { mutableListOf() } += Arc(b.id, c, e.kind)
            adj.getOrPut(b.id) { mutableListOf() } += Arc(a.id, c, e.kind)
        }
        for ((a, b, c) in extra) {
            adj.getOrPut(a) { mutableListOf() } += Arc(b, c, EdgeKind.WALK)
            adj.getOrPut(b) { mutableListOf() } += Arc(a, c, EdgeKind.WALK)
        }
        return adj
    }

    /**
     * Plan the walkable route from a free position to a Visual Pin.
     * A* over the venue graph; the start position is snapped onto the nearest walkable edge.
     */
    fun planToPin(start: Vec2, floor: Int, pin: VisualPin): Route? {
        val goal = venue.node(pin.nodeId) ?: return null
        val route = plan(start, floor, goal.id, pin.name) ?: return null
        // add the final short leg from the graph node to the exact pin point
        if (pin.pos.distanceTo(goal.pos) > 0.3 && pin.floor == goal.floor) {
            val pts = route.points + RoutePoint(pin.pos, pin.floor, null, pin.name, NodeType.ROOM)
            return Route(pts, pin.name, route.transitionKinds)
        }
        return route
    }

    fun plan(start: Vec2, floor: Int, goalId: String, destinationName: String): Route? {
        val goal = venue.node(goalId) ?: return null
        val snap = venue.snapToGraph(start, floor) ?: return null
        val startId = "__start"
        val a = venue.node(snap.edge.a)!!
        val b = venue.node(snap.edge.b)!!
        val extra = listOf(
            Triple(startId, a.id, snap.point.distanceTo(a.pos)),
            Triple(startId, b.id, snap.point.distanceTo(b.pos)),
        )
        val adj = adjacency(extra)
        val posOf = { id: String -> if (id == startId) snap.point else venue.node(id)!!.pos }
        val floorOf = { id: String -> if (id == startId) floor else venue.node(id)!!.floor }

        // A*: heuristic = planar distance when on the same floor as goal, 0 otherwise (admissible)
        val g = HashMap<String, Double>()
        val prev = HashMap<String, String>()
        val prevKind = HashMap<String, EdgeKind>()
        val open = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        fun h(id: String) = if (floorOf(id) == goal.floor) posOf(id).distanceTo(goal.pos) else 0.0
        g[startId] = 0.0
        open += startId to h(startId)
        val closed = HashSet<String>()
        while (open.isNotEmpty()) {
            val (cur, _) = open.poll()
            if (!closed.add(cur)) continue
            if (cur == goal.id) break
            for (arc in adj[cur].orEmpty()) {
                val ng = g[cur]!! + arc.cost
                if (ng < (g[arc.to] ?: Double.MAX_VALUE)) {
                    g[arc.to] = ng
                    prev[arc.to] = cur
                    prevKind[arc.to] = arc.kind
                    open += arc.to to (ng + h(arc.to))
                }
            }
        }
        if (goal.id != startId && prev[goal.id] == null) return null

        val ids = ArrayList<String>()
        var c: String? = goal.id
        while (c != null) {
            ids += c; c = prev[c]
        }
        ids.reverse()
        val pts = ArrayList<RoutePoint>()
        val transitions = HashMap<Int, EdgeKind>()
        for ((idx, id) in ids.withIndex()) {
            if (id == startId) {
                pts += RoutePoint(snap.point, floor, null, "")
            } else {
                val n = venue.node(id)!!
                pts += RoutePoint(n.pos, n.floor, n.id, n.name, n.type)
            }
            if (idx > 0) {
                val k = prevKind[id]
                if (k != null && k != EdgeKind.WALK) transitions[idx - 1] = k
            }
        }
        // drop a degenerate first point that coincides with the next one
        if (pts.size >= 2 && pts[0].pos.distanceTo(pts[1].pos) < 0.05 && pts[0].floor == pts[1].floor) {
            pts.removeAt(0)
            val shifted = transitions.mapKeys { it.key - 1 }
            transitions.clear(); transitions.putAll(shifted)
        }
        return Route(pts, destinationName, transitions)
    }
}
