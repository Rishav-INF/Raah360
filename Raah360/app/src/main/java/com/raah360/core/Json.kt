package com.raah360.core

/**
 * Tiny JSON reader/writer (no Android dependency) so the venue format can be
 * unit-tested on the JVM and shared between phones as a plain file.
 * Values: Map<String, Any?>, List<Any?>, String, Double, Boolean, null.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "Trailing data at $i" }; v }

    fun write(v: Any?, indent: Int = 0): String = StringBuilder().also { w(it, v, indent, 0) }.toString()

    private fun w(sb: StringBuilder, v: Any?, ind: Int, depth: Int) {
        val nl = if (ind > 0) "\n" else ""
        fun pad(d: Int) = if (ind > 0) " ".repeat(ind * d) else ""
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Float -> num(sb, v.toDouble())
            is Double -> num(sb, v)
            is Number -> num(sb, v.toDouble())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{").append(nl)
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(",").append(nl)
                    first = false
                    sb.append(pad(depth + 1)); str(sb, k.toString()); sb.append(if (ind > 0) ": " else ":")
                    w(sb, value, ind, depth + 1)
                }
                sb.append(nl).append(pad(depth)).append("}")
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                // short numeric arrays stay on one line
                val inline = v.all { it is Number }
                sb.append("[")
                if (!inline) sb.append(nl)
                v.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(",").append(if (inline) "" else nl)
                    if (!inline) sb.append(pad(depth + 1))
                    w(sb, item, ind, depth + 1)
                }
                if (!inline) sb.append(nl).append(pad(depth))
                sb.append("]")
            }
            is FloatArray -> w(sb, v.map { it.toDouble() }, ind, depth)
            else -> str(sb, v.toString())
        }
    }

    private fun num(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else {
            // 4 decimals is mm precision for map data, enough for signatures too
            sb.append(String.format(java.util.Locale.US, "%.4f", d).trimEnd('0').trimEnd('.'))
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("Bad char '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "Bad literal at $i" }; i += word.length; return v }
        fun number(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun string(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> error("Expected , or ] at ${i - 1}") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = string(); ws()
                require(s[i++] == ':') { "Expected : at ${i - 1}" }
                out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> error("Expected , or } at ${i - 1}") }
            }
        }
    }
}

/** Venue <-> JSON. Format documented in assets/venues/README.md. */
object VenueCodec {
    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj() = this as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun Any?.list() = (this as? List<Any?>) ?: emptyList()
    private fun Any?.d() = (this as Number).toDouble()
    private fun Any?.dn() = (this as? Number)?.toDouble()
    private fun Map<String, Any?>.s(k: String, def: String = "") = (this[k] as? String) ?: def
    private fun Map<String, Any?>.vec(k: String): Vec2 { val l = this[k].list(); return Vec2(l[0].d(), l[1].d()) }

    fun decode(text: String): Venue {
        val o = Json.parse(text).obj()
        return Venue(
            id = o.s("id"),
            name = o.s("name"),
            address = o.s("address"),
            lat = o["lat"].dn(),
            lng = o["lng"].dn(),
            floors = o["floors"].list().map { val f = it.obj(); FloorInfo(f["level"].d().toInt(), f.s("name")) },
            nodes = o["nodes"].list().map {
                val n = it.obj()
                MapNode(n.s("id"), n.s("name"), n.vec("pos"), n["floor"].d().toInt(),
                    runCatching { NodeType.valueOf(n.s("type", "WAYPOINT")) }.getOrDefault(NodeType.WAYPOINT))
            },
            edges = o["edges"].list().map {
                val l = it.list()
                MapEdge(l[0] as String, l[1] as String,
                    if (l.size > 2) runCatching { EdgeKind.valueOf(l[2] as String) }.getOrDefault(EdgeKind.WALK) else EdgeKind.WALK)
            },
            landmarks = o["landmarks"].list().map {
                val l = it.obj()
                Landmark(l.s("id"), l.s("name"), l["floor"].d().toInt(), l.vec("pos"), l["yaw"].d(), l["width"].dn() ?: 0.2,
                    l["image"] as? String, l["located"] as? Boolean ?: false, l.s("hint"))
            },
            pins = o["pins"].list().map {
                val p = it.obj()
                VisualPin(p.s("id"), p.s("name"), p.s("description"), p["floor"].d().toInt(), p.s("node"), p.vec("pos"),
                    p["facing"].dn(), p.s("category", "Place"), p["image"] as? String,
                    (p["signature"] as? List<*>)?.map { (it as Number).toFloat() }?.toFloatArray(),
                    p["lat"].dn(), p["lng"].dn(), p["geoAcc"].dn())
            },
            revision = (o["revision"].dn() ?: 1.0).toInt(),
            survey = (o["survey"].dn() ?: 1.0).toInt(),
            northDeg = o["north"].dn(),
            floorHeightM = o["floorHeight"].dn() ?: 3.6,
        )
    }

    fun encode(v: Venue): String {
        fun vec(p: Vec2) = listOf(p.x, p.y)
        val m = linkedMapOf<String, Any?>(
            "format" to "raah360-venue-1",
            "id" to v.id, "name" to v.name, "address" to v.address, "lat" to v.lat, "lng" to v.lng,
            "revision" to v.revision, "survey" to v.survey, "north" to v.northDeg, "floorHeight" to v.floorHeightM,
            "floors" to v.floors.map { mapOf("level" to it.level, "name" to it.name) },
            "nodes" to v.nodes.map { linkedMapOf("id" to it.id, "name" to it.name, "floor" to it.floor, "type" to it.type.name, "pos" to vec(it.pos)) },
            "edges" to v.edges.map { if (it.kind == EdgeKind.WALK) listOf(it.a, it.b) else listOf(it.a, it.b, it.kind.name) },
            "landmarks" to v.landmarks.map {
                linkedMapOf("id" to it.id, "name" to it.name, "floor" to it.floor, "pos" to vec(it.pos), "yaw" to it.yawDeg,
                    "width" to it.widthM, "image" to it.image, "located" to it.located, "hint" to it.hint)
            },
            "pins" to v.pins.map {
                linkedMapOf("id" to it.id, "name" to it.name, "description" to it.description, "category" to it.category,
                    "floor" to it.floor, "node" to it.nodeId, "pos" to vec(it.pos), "facing" to it.facingDeg,
                    "image" to it.image, "signature" to it.signature?.map { f -> f.toDouble() }).apply {
                    if (it.lat != null && it.lng != null) { put("lat", it.lat); put("lng", it.lng); put("geoAcc", it.geoAccM) }
                }
            },
        )
        return Json.write(m, 1)
    }
}
