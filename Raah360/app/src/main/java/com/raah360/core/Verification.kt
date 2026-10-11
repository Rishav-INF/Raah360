package com.raah360.core

import kotlin.math.roundToInt

enum class VerifyResult { SEARCHING, EXACT_ARRIVAL, WRONG_SPOT }

data class VerifyState(
    val result: VerifyResult,
    val similarity: Double?,
    val distanceM: Double?,
    val message: String,
    val detail: String = "",
)

data class VerifyConfig(
    /** Visual similarity needed to call it a match (tune with the debug meter on site). */
    val matchThreshold: Double = 0.74,
    /** Below this the scene is clearly something else. */
    val mismatchThreshold: Double = 0.60,
    val holdMs: Long = 800,
    val nearM: Double = 2.5,
    val facingToleranceDeg: Double = 50.0,
    val wrongSpotHoldMs: Long = 2500,
)

/**
 * Final step: is the user at the Visual Pin, or at a similar-looking place nearby?
 * Combines map position (from the localised AR pose) with the pin's visual signature.
 */
class ArrivalVerifier(private val pin: VisualPin, private val cfg: VerifyConfig = VerifyConfig()) {
    private var matchSince = -1L
    private var wrongSince = -1L
    private var locked: VerifyState? = null
    private var ema: Double? = null
    private var lastMs = 0L

    fun reset() { matchSince = -1; wrongSince = -1; locked = null; ema = null }

    fun update(pose: Pose?, frameSig: FloatArray?, now: Long): VerifyState {
        locked?.let { return it }
        val dist = pose?.let { if (it.floor == pin.floor) it.pos.distanceTo(pin.pos) else Double.MAX_VALUE }
        val rawSim = if (pin.signature != null && frameSig != null) ImageSignature.similarity(pin.signature, frameSig) else null
        // smooth over ~0.4 s: single blurry frames should neither confirm nor reject
        if (rawSim != null) {
            val prev = ema
            val k = if (prev == null) 1.0 else 1.0 - kotlin.math.exp(-((now - lastMs).coerceAtLeast(0)) / 400.0)
            ema = if (prev == null) rawSim else prev + (rawSim - prev) * k
            lastMs = now
        }
        val sim = if (rawSim != null) ema else null
        val facingOk = pose == null || pin.facingDeg == null || Angles.diff(pose.headingDeg, pin.facingDeg) <= cfg.facingToleranceDeg
        val near = dist == null || dist <= cfg.nearM

        val visualMatch = sim != null && sim >= cfg.matchThreshold
        val visualMismatch = sim != null && sim < cfg.mismatchThreshold
        // without a reference photo we can only verify by position + facing
        val positionOnlyMatch = pin.signature == null && dist != null && dist <= 1.5 && facingOk

        val matchNow = (visualMatch && near && (facingOk || sim!! >= cfg.matchThreshold + 0.05)) || positionOnlyMatch
        if (matchNow) {
            if (matchSince < 0) matchSince = now
            if (now - matchSince >= cfg.holdMs) {
                val how = if (pin.signature == null) "Verified by position" else "Visual match ${(sim!! * 100).roundToInt()}%"
                return VerifyState(VerifyResult.EXACT_ARRIVAL, sim, dist, "Exact destination verified", how).also { locked = it }
            }
        } else matchSince = -1

        // Wrong spot: looks like the pin but we are too far away, or we are close but it looks different
        val farButSimilar = visualMatch && dist != null && dist > cfg.nearM
        val nearButDifferent = visualMismatch && near && facingOk
        if (farButSimilar || nearButDifferent || (pin.signature == null && dist != null && dist > 4.0)) {
            if (wrongSince < 0) wrongSince = now
            if (now - wrongSince >= cfg.wrongSpotHoldMs) {
                return VerifyState(VerifyResult.WRONG_SPOT, sim, dist, "Wrong spot", directionHint(pose))
            }
        } else wrongSince = -1

        val hint = when {
            sim == null && pin.signature != null -> "Point the camera at ${pin.name}"
            !facingOk -> "Face ${pin.name}"
            else -> "Hold the camera steady on ${pin.name}"
        }
        return VerifyState(VerifyResult.SEARCHING, sim, dist, "Verifying…", hint)
    }

    /** "The destination is 4 m to your left" */
    fun directionHint(pose: Pose?): String {
        if (pose == null || pose.floor != pin.floor) return "This does not look like ${pin.name}."
        val d = pose.pos.distanceTo(pin.pos)
        if (d < 1.0) return "This does not look like ${pin.name}. Look around you."
        val rel = Angles.norm(Angles.bearing(pose.pos, pin.pos) - pose.headingDeg)
        val dir = Sector.of(rel).label.lowercase()
        val where = if (dir == "front") "ahead of you" else "to your $dir"
        return "${pin.name} is about ${d.roundToInt()} m $where."
    }
}
