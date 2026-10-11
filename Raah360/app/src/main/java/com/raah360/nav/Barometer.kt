package com.raah360.nav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Air pressure (hPa) for telling floors apart. Many phones have no barometer: check [available]. */
class Barometer(context: Context, private val onPressure: (Double) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val available: Boolean get() = sensor != null

    fun start() { sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) = onPressure(e.values[0].toDouble())
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
