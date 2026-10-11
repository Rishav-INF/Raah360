package com.raah360.core

import kotlin.math.roundToInt

data class Speech(val text: String, val urgent: Boolean = false, val key: String = text)

/**
 * Decides WHEN to speak. Speaks only on meaningful events (route start, upcoming turn,
 * turn now, off-route, lost, destination near, arrival) and never repeats every second.
 * Phrases are derived from the same [GuidanceState] the screen shows, so audio and the
 * green sector always agree.
 */
class VoicePrompter(
    private val minGapMs: Long = 2500,
    private val reassureEveryMs: Long = 20000,
) {
    private var lastSpokeMs = -100000L
    private val announced = HashSet<String>()
    private val cooldowns = HashMap<String, Long>()
    private var lastPhase: NavPhase? = null
    private var aroundSince = -1L
    private var started = false
    var destinationName: String = "your destination"
    /** False on the 3D map, where there's no camera view to verify with. */
    var cameraVerify = true

    fun reset() {
        lastSpokeMs = -100000L; announced.clear(); cooldowns.clear(); lastPhase = null; aroundSince = -1; started = false
    }

    private fun cooled(key: String, now: Long, cooldownMs: Long): Boolean {
        val t = cooldowns[key] ?: return true
        return now - t >= cooldownMs
    }

    private fun emit(s: Speech, now: Long): Speech {
        lastSpokeMs = now
        cooldowns[s.key] = now
        return s
    }

    private fun m(d: Double): String {
        val r = d.roundToInt().coerceAtLeast(1)
        return if (r == 1) "1 metre" else "$r metres"
    }

    fun update(st: GuidanceState, now: Long): Speech? {
        val prevPhase = lastPhase
        lastPhase = st.phase
        val gapOk = now - lastSpokeMs >= minGapMs

        when (st.phase) {
            NavPhase.LOCALIZING -> {
                if (prevPhase != NavPhase.LOCALIZING && cooled("loc", now, 15000))
                    return emit(Speech("Scan a nearby landmark to start.", key = "loc"), now)
                return null
            }
            NavPhase.LOST -> {
                if (cooled("lost", now, 15000)) return emit(Speech("Location lost. Scan a nearby mapped landmark.", true, "lost"), now)
                return null
            }
            NavPhase.UNSTABLE -> {
                if (prevPhase != NavPhase.UNSTABLE && cooled("unstable", now, 20000) && gapOk)
                    return emit(Speech("Tracking unstable. Move your phone slowly.", key = "unstable"), now)
                return null
            }
            NavPhase.ARRIVED -> {
                if ("arrived" !in announced) {
                    announced += "arrived"
                    val where = st.towardName ?: "your destination"
                    val text = if (cameraVerify) "You have arrived near $where. Point your camera at it to verify." else "You have arrived at $where."
                    return emit(Speech(text, true, "arrived"), now)
                }
                return null
            }
            NavPhase.FLOOR_CHANGE -> {
                val key = "floor-${st.nextManeuver?.atS}"
                if (key !in announced) {
                    announced += key
                    return emit(Speech("${st.headline.lowercase().replaceFirstChar { it.uppercase() }}. ${st.nextAction}.", true, key), now)
                }
                return null
            }
            NavPhase.OFF_ROUTE -> {
                if (cooled("off", now, 8000)) return emit(Speech("You are moving away from the route.", true, "off"), now)
                return null
            }
            NavPhase.NAVIGATING -> Unit
        }

        // ---- NAVIGATING ----
        if (!started) {
            started = true
            val first = when (st.sector) {
                Sector.FRONT -> "Proceed straight"
                null -> "Start walking"
                else -> st.headline.lowercase().replaceFirstChar { it.uppercase() }
            }
            return emit(Speech("$first. ${m(st.remainingM)} to $destinationName.", key = "start"), now)
        }

        // turned the wrong way for a while
        if (st.sector == Sector.BACK || st.sector == Sector.BACK_LEFT || st.sector == Sector.BACK_RIGHT) {
            if (aroundSince < 0) aroundSince = now
            if (now - aroundSince > 1500 && cooled("around", now, 8000) && gapOk) {
                return emit(Speech("Turn around.", true, "around"), now)
            }
        } else aroundSince = -1

        val man = st.nextManeuver
        val d = st.distToManeuverM
        if (man != null && d != null) {
            val id = "${man.kind}-${"%.1f".format(man.atS)}"
            when (man.kind) {
                ManeuverKind.TURN -> {
                    val word = GuidanceEngine.turnWord(man.turnDeg)
                    val at = if (man.placeName.isNotBlank()) " at ${man.placeName}" else ""
                    if (d <= 2.5 && "now-$id" !in announced) {
                        announced += "now-$id"; announced += "soon-$id"
                        return emit(Speech("Turn $word$at.", true, "now-$id"), now)
                    }
                    if (d in 2.5..8.0 && "soon-$id" !in announced && gapOk) {
                        announced += "soon-$id"
                        return emit(Speech("In ${m(d)}, turn $word$at.", key = "soon-$id"), now)
                    }
                }
                ManeuverKind.ARRIVE -> {
                    if (d <= 6.0 && "near" !in announced && gapOk) {
                        announced += "near"
                        val side = when {
                            man.turnDeg > 30 -> "${man.placeName} is ahead on your right."
                            man.turnDeg < -30 -> "${man.placeName} is ahead on your left."
                            else -> "Your destination is ahead."
                        }
                        return emit(Speech(side, key = "near"), now)
                    }
                }
                ManeuverKind.FLOOR_CHANGE -> {
                    if (d <= 8.0 && "soonfloor-$id" !in announced && gapOk) {
                        announced += "soonfloor-$id"
                        return emit(Speech("In ${m(d)}, take the ${viaWord(man.via)} to floor ${man.toFloor}.", key = "soonfloor-$id"), now)
                    }
                }
            }
        }

        // gentle reassurance on long straight stretches
        if (st.sector == Sector.FRONT && now - lastSpokeMs > reassureEveryMs && d != null && d > 8) {
            return emit(Speech("Continue straight for ${m(d)}.", key = "reassure"), now)
        }
        return null
    }

    private fun viaWord(k: EdgeKind?) = when (k) { EdgeKind.LIFT -> "lift"; EdgeKind.ESCALATOR -> "escalator"; else -> "stairs" }

    /** Reroute and verification messages are pushed by the controller. */
    fun forced(text: String, now: Long): Speech = emit(Speech(text, true), now)
}
