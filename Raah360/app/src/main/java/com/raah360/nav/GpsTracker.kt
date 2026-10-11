package com.raah360.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live GPS / network location, best recent fix first. Uses the platform LocationManager (no Play Services). */
class GpsTracker(context: Context) {
    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Location?>(null)
    val fix: StateFlow<Location?> = _fix
    private var running = false

    // all four methods implemented: older Androids have no default bodies and would crash
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = offer(l)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val locationOn: Boolean
        get() = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission) return
        running = true
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let(::offer)
            runCatching { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { lm.removeUpdates(listener) }
    }

    /** A fix fresh and accurate enough to remember a place by. */
    fun usableFix(maxAgeMs: Long = 60_000, maxAccuracyM: Float = 30f): Location? =
        _fix.value?.takeIf { System.currentTimeMillis() - it.time <= maxAgeMs && it.accuracy <= maxAccuracyM }

    private fun offer(l: Location) {
        val cur = _fix.value
        // newer wins unless it is much worse than a still-recent fix
        if (cur == null || l.time - cur.time > 5000 || l.accuracy <= cur.accuracy * 1.5f) _fix.value = l
    }
}
