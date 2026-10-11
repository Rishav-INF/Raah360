package com.raah360.nav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.raah360.core.StepPeakDetector
import kotlin.math.sqrt

/** Calls [onStep] for every step the accelerometer feels. */
class StepTracker(context: Context, private val onStep: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = StepPeakDetector()

    val available: Boolean get() = acc != null

    fun start() { acc?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        val m = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
        if (detector.feed(m, e.timestamp)) onStep()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
