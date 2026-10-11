package com.raah360.core

enum class NodeType { WAYPOINT, JUNCTION, CORNER, ENTRANCE, ROOM, STAIRS, LIFT, ESCALATOR, FACILITY }

enum class EdgeKind { WALK, STAIRS, LIFT, ESCALATOR }

/** A point a person can stand on. Graph nodes are on corridor centre-lines. */
data class MapNode(
    val id: String,
    val name: String,
    val pos: Vec2,
    val floor: Int,
    val type: NodeType = NodeType.WAYPOINT,
) {
    /** Unnamed waypoints are never announced ("toward ..."). */
    val isNamed: Boolean get() = name.isNotBlank()
}

/** A walkable connection. No edge = no way through (walls, rooms, restricted areas). */
data class MapEdge(
    val a: String,
    val b: String,
    val kind: EdgeKind = EdgeKind.WALK,
)

/**
 * A visual landmark with a known pose on the map. Detected with ARCore Augmented Images
 * to align a fresh AR session with the venue map, and again later to correct drift.
 */
data class Landmark(
    val id: String,
    val name: String,
    val floor: Int,
    val pos: Vec2,
    /** Heading the landmark's face points to (towards a viewer standing in front of it). */
    val yawDeg: Double,
    /** Physical width of the printed marker / sign in metres (helps ARCore). */
    val widthM: Double,
    /** Image file name inside the venue folder, or "asset:markers/xx.png". */
    val image: String?,
    /** True once the pose was measured on site with the Mapper. */
    val located: Boolean,
    val hint: String = "",
)

/** Exact destination: what the place looks like + where it is on the map. */
data class VisualPin(
    val id: String,
    val name: String,
    val description: String,
    val floor: Int,
    /** Graph node the route ends at. */
    val nodeId: String,
    /** Exact point to stand at (usually in front of the door / object). */
    val pos: Vec2,
    /** Heading you face when looking at the destination, if known. */
    val facingDeg: Double?,
    val category: String = "Place",
    /** Reference photo file name inside the venue folder. */
    val image: String? = null,
    /** Visual signature of the reference photo (see [ImageSignature]). */
    val signature: FloatArray? = null,
    /** GPS position learnt from people confirming "I'm here" at this place (null until then). */
    val lat: Double? = null,
    val lng: Double? = null,
    /** Accuracy of [lat]/[lng] in metres; shrinks as fixes are averaged. */
    val geoAccM: Double? = null,
) {
    override fun equals(other: Any?) = other is VisualPin && other.id == id && other.name == name &&
        other.pos == pos && other.image == image && other.nodeId == nodeId && other.facingDeg == facingDeg &&
        other.description == description && other.lat == lat && other.lng == lng &&
        other.signature.contentEqualsNullable(signature)

    override fun hashCode() = id.hashCode()
}

private fun FloatArray?.contentEqualsNullable(o: FloatArray?): Boolean =
    if (this == null || o == null) this === o else this.contentEquals(o)

data class FloorInfo(val level: Int, val name: String)

data class Venue(
    val id: String,
    val name: String,
    val address: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val floors: List<FloorInfo> = emptyList(),
    val nodes: List<MapNode> = emptyList(),
    val edges: List<MapEdge> = emptyList(),
    val landmarks: List<Landmark> = emptyList(),
    val pins: List<VisualPin> = emptyList(),
    val revision: Int = 1,
    /** Version of the shipped survey this map is based on; edits bump [revision], not this. */
    val survey: Int = 1,
    /** Compass bearing that map +y points to (null until calibrated on site). Turns the compass into map headings. */
    val northDeg: Double? = null,
    /** Storey height, for telling floors apart with the barometer. */
    val floorHeightM: Double = 3.6,
) {
    private val nodeIndex: Map<String, MapNode> by lazy { nodes.associateBy { it.id } }
    fun node(id: String): MapNode? = nodeIndex[id]
    fun pin(id: String): VisualPin? = pins.firstOrNull { it.id == id }
    fun landmark(id: String): Landmark? = landmarks.firstOrNull { it.id == id }
    fun floorName(level: Int) = floors.firstOrNull { it.level == level }?.name ?: "Floor $level"

    /** Nearest walkable point on any WALK edge of [floor]. */
    fun snapToGraph(p: Vec2, floor: Int): GraphSnap? {
        var best: GraphSnap? = null
        for (e in edges) {
            if (e.kind != EdgeKind.WALK) continue
            val a = node(e.a) ?: continue
            val b = node(e.b) ?: continue
            if (a.floor != floor || b.floor != floor) continue
            val pr = projectOnSegment(p, a.pos, b.pos)
            if (best == null || pr.distance < best.distance) best = GraphSnap(e, pr.point, pr.t, pr.distance)
        }
        if (best == null) {
            // graph without edges on that floor: fall back to nearest node
            val n = nodes.filter { it.floor == floor }.minByOrNull { it.pos.distanceTo(p) } ?: return null
            return GraphSnap(MapEdge(n.id, n.id), n.pos, 0.0, n.pos.distanceTo(p))
        }
        return best
    }

    fun nearestNode(p: Vec2, floor: Int): MapNode? =
        nodes.filter { it.floor == floor }.minByOrNull { it.pos.distanceTo(p) }
}

data class GraphSnap(val edge: MapEdge, val point: Vec2, val t: Double, val distance: Double)
