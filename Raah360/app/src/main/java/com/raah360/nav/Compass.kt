package com.raah360.nav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.raah360.core.HeadingSmoother
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which way the visitor faces (compass bearing, degrees). Works whether the phone is held flat
 * or upright: it combines where the top edge and the back camera point, like the AR mode does.
 * Indoors, steel and wiring can bend the compass; the app's calibration and route refinement absorb that.
 */
class Compass(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private val smoother = HeadingSmoother(0.15)

    val available: Boolean get() = sensor != null
    @Volatile var headingDeg: Double? = null
        private set

    fun start() { sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // world frame: x = east, y = north. Device +Y (top edge) = column 1, device -Z (camera) = -column 2.
        val east = r[1] - r[2]
        val north = r[4] - r[5]
        if (hypot(east, north) < 0.2) return
        headingDeg = smoother.feed(Math.toDegrees(atan2(east.toDouble(), north.toDouble())))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
