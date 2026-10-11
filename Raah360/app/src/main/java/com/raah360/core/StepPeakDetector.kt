package com.raah360.core

/**
 * Counts steps from the accelerometer: each step is one peak of (|a| − g) above [highMs2],
 * re-armed once the signal drops below [lowMs2]. No permission needed, unlike the step sensor.
 */
class StepPeakDetector(
    private val highMs2: Double = 1.2,
    private val lowMs2: Double = 0.3,
    private val minGapNs: Long = 300_000_000L,
    private val smoothing: Double = 0.3,
) {
    private var filtered = 0.0
    private var armed = true
    private var lastStepNs = Long.MIN_VALUE / 2

    /** Feed |a| − g in m/s²; returns true when this sample completes a step. */
    fun feed(accMinusG: Double, timeNs: Long): Boolean {
        filtered += smoothing * (accMinusG - filtered)
        if (filtered < lowMs2) armed = true
        if (armed && filtered > highMs2 && timeNs - lastStepNs > minGapNs) {
            armed = false
            lastStepNs = timeNs
            return true
        }
        return false
    }
}
