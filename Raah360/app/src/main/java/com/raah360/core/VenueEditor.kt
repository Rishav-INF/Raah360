package com.raah360.core

/** Immutable edit operations used by the Mapper. Every call returns a new [Venue]. */
object VenueEditor {

    fun newId(prefix: String, existing: Collection<String>): String {
        var i = existing.size + 1
        while ("$prefix$i" in existing) i++
        return "$prefix$i"
    }

    fun addNode(v: Venue, pos: Vec2, floor: Int, name: String = "", type: NodeType = NodeType.WAYPOINT, connectTo: String? = null): Pair<Venue, MapNode> {
        val id = newId("n", v.nodes.map { it.id })
        val n = MapNode(id, name, pos, floor, type)
        val edges = if (connectTo != null && v.node(connectTo) != null) v.edges + MapEdge(connectTo, id) else v.edges
        return v.copy(nodes = v.nodes + n, edges = edges, revision = v.revision + 1) to n
    }

    fun connect(v: Venue, a: String, b: String, kind: EdgeKind = EdgeKind.WALK): Venue {
        if (a == b || v.node(a) == null || v.node(b) == null) return v
        if (v.edges.any { (it.a == a && it.b == b) || (it.a == b && it.b == a) }) return v
        return v.copy(edges = v.edges + MapEdge(a, b, kind), revision = v.revision + 1)
    }

    fun disconnect(v: Venue, a: String, b: String): Venue =
        v.copy(edges = v.edges.filterNot { (it.a == a && it.b == b) || (it.a == b && it.b == a) }, revision = v.revision + 1)

    /** Removes a node; its two neighbours (if exactly two) are reconnected so corridors stay continuous. */
    fun removeNode(v: Venue, id: String): Venue {
        if (v.pins.any { it.nodeId == id }) return v // keep nodes that a pin depends on
        val neigh = v.edges.filter { it.a == id || it.b == id }.map { if (it.a == id) it.b else it.a }.distinct()
        var edges = v.edges.filterNot { it.a == id || it.b == id }
        if (neigh.size == 2) edges = edges + MapEdge(neigh[0], neigh[1])
        return v.copy(nodes = v.nodes.filterNot { it.id == id }, edges = edges, revision = v.revision + 1)
    }

    fun renameNode(v: Venue, id: String, name: String, type: NodeType? = null): Venue =
        v.copy(nodes = v.nodes.map { if (it.id == id) it.copy(name = name, type = type ?: it.type) else it }, revision = v.revision + 1)

    fun moveNode(v: Venue, id: String, pos: Vec2): Venue =
        v.copy(nodes = v.nodes.map { if (it.id == id) it.copy(pos = pos) else it }, revision = v.revision + 1)

    fun upsertLandmark(v: Venue, l: Landmark): Venue {
        val list = if (v.landmarks.any { it.id == l.id }) v.landmarks.map { if (it.id == l.id) l else it } else v.landmarks + l
        return v.copy(landmarks = list, revision = v.revision + 1)
    }

    fun removeLandmark(v: Venue, id: String) = v.copy(landmarks = v.landmarks.filterNot { it.id == id }, revision = v.revision + 1)

    fun upsertPin(v: Venue, p: VisualPin): Venue {
        val list = if (v.pins.any { it.id == p.id }) v.pins.map { if (it.id == p.id) p else it } else v.pins + p
        return v.copy(pins = list, revision = v.revision + 1)
    }

    fun removePin(v: Venue, id: String) = v.copy(pins = v.pins.filterNot { it.id == id }, revision = v.revision + 1)

    /**
     * Create a pin at the user's position: the route ends at the nearest graph point.
     * If no node is within 1 m, a node is inserted on the nearest corridor edge.
     */
    fun pinHere(v: Venue, name: String, pos: Vec2, floor: Int, facing: Double?, category: String = "Place"): Pair<Venue, VisualPin> {
        var venue = v
        val near = v.nodes.filter { it.floor == floor }.minByOrNull { it.pos.distanceTo(pos) }
        val nodeId: String
        if (near != null && near.pos.distanceTo(pos) <= 1.0) {
            nodeId = near.id
        } else {
            val snap = v.snapToGraph(pos, floor)
            if (snap != null && snap.edge.a != snap.edge.b && snap.distance < 6.0) {
                val (v2, n) = splitEdge(v, snap.edge, snap.point, name)
                venue = v2; nodeId = n.id
            } else {
                val (v2, n) = addNode(v, pos, floor, name, NodeType.ROOM, near?.id)
                venue = v2; nodeId = n.id
            }
        }
        val pin = VisualPin(newId("p", venue.pins.map { it.id }), name, "", floor, nodeId, pos, facing, category)
        return upsertPin(venue, pin) to pin
    }

    fun splitEdge(v: Venue, e: MapEdge, at: Vec2, name: String = ""): Pair<Venue, MapNode> {
        val a = v.node(e.a)!!
        val (v1, n) = addNode(v, at, a.floor, name, NodeType.WAYPOINT)
        val edges = v1.edges.filterNot { it == e } + MapEdge(e.a, n.id, e.kind) + MapEdge(n.id, e.b, e.kind)
        return v1.copy(edges = edges) to n
    }

    /** Connected-component check used by the Mapper's health panel. */
    fun unreachableNodes(v: Venue): List<MapNode> {
        if (v.nodes.isEmpty()) return emptyList()
        val adj = HashMap<String, MutableList<String>>()
        for (e in v.edges) {
            adj.getOrPut(e.a) { mutableListOf() } += e.b
            adj.getOrPut(e.b) { mutableListOf() } += e.a
        }
        val start = v.nodes.maxByOrNull { adj[it.id]?.size ?: 0 }!!.id
        val seen = HashSet<String>()
        val stack = ArrayDeque(listOf(start))
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (!seen.add(c)) continue
            adj[c]?.forEach { if (it !in seen) stack.addLast(it) }
        }
        return v.nodes.filter { it.id !in seen }
    }
}
