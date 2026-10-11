package com.raah360.core

/**
 * The whole Locate → Route → Guide → Verify loop for one Visual Pin.
 * Feed it a pose (already in map coordinates) every frame; it returns what to show and say.
 */
class NavigationSession(
    val venue: Venue,
    val pin: VisualPin,
    private val guidanceConfig: GuidanceConfig = GuidanceConfig(),
    verifyConfig: VerifyConfig = VerifyConfig(),
) {
    data class Frame(
        val guidance: GuidanceState,
        val speech: Speech?,
        val verify: VerifyState?,
        val route: Route?,
        val routeChanged: Boolean,
    )

    private val planner = RoutePlanner(venue)
    var route: Route? = null
        private set
    private var engine: GuidanceEngine? = null
    val prompter = VoicePrompter().also { it.destinationName = pin.name }
    private val verifier = ArrivalVerifier(pin, verifyConfig)
    private var lastRerouteMs = -100000L
    private var lastVerifyResult: VerifyResult? = null
    private var lastWrongSpeechMs = -100000L
    var reroutes = 0
        private set

    fun restart() {
        route = null; engine = null; prompter.reset(); verifier.reset(); lastVerifyResult = null
        prompter.destinationName = pin.name
    }

    fun update(pose: Pose?, quality: TrackingQuality, now: Long, frameSignature: FloatArray? = null): Frame {
        var changed = false
        var forced: Speech? = null
        if (route == null && pose != null && (quality == TrackingQuality.GOOD || quality == TrackingQuality.UNSTABLE)) {
            val r = planner.planToPin(pose.pos, pose.floor, pin)
            if (r != null) {
                route = r; engine = GuidanceEngine(r, guidanceConfig); changed = true
            } else {
                return Frame(
                    GuidanceState(NavPhase.OFF_ROUTE, headline = "NO ROUTE", nextAction = "No walkable route from here to ${pin.name}"),
                    null, null, null, false
                )
            }
        }
        val eng = engine
        if (eng == null) {
            val st = GuidanceState(if (quality == TrackingQuality.LOST) NavPhase.LOST else NavPhase.LOCALIZING,
                headline = if (quality == TrackingQuality.LOST) "LOCATION LOST" else "SCAN A LANDMARK",
                nextAction = "Point the camera at a mapped landmark")
            return Frame(st, prompter.update(st, now), null, null, false)
        }

        var st = eng.update(pose, quality, now)

        // automatic re-routing when the user has left the route
        if (st.phase == NavPhase.OFF_ROUTE && pose != null && now - lastRerouteMs > 4000) {
            val r = planner.planToPin(pose.pos, pose.floor, pin)
            if (r != null) {
                lastRerouteMs = now
                reroutes++
                route = r; eng.reset(r); changed = true
                forced = prompter.forced("You moved away from the route. Recalculating.", now)
                st = eng.update(pose, quality, now)
            }
        }

        var verify: VerifyState? = null
        if (st.phase == NavPhase.ARRIVED) {
            verify = verifier.update(pose, frameSignature, now)
            if (verify.result != lastVerifyResult) {
                if (verify.result == VerifyResult.EXACT_ARRIVAL) forced = prompter.forced("Exact destination verified. You have reached ${pin.name}.", now)
                if (verify.result == VerifyResult.WRONG_SPOT && now - lastWrongSpeechMs > 10000) {
                    lastWrongSpeechMs = now
                    forced = prompter.forced("Wrong spot. ${verify.detail}", now)
                }
                lastVerifyResult = verify.result
            }
        }
        val speech = forced ?: prompter.update(st, now)
        return Frame(st, speech, verify, route, changed)
    }
}
