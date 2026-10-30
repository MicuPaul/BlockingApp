package com.example.blockingapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.TimeUnit

data class ZoneArea(val lat: Double, val lon: Double, val radius: Int)

/**
 * Blocking applies only inside the zone (everywhere if none is set). It fails closed: with no
 * location access, location off, a stale, imprecise or mock fix, the phone counts as inside.
 */
object Zone {
    const val MIN_RADIUS = 100
    const val MAX_RADIUS = 50_000
    private val STALE_MS = TimeUnit.MINUTES.toMillis(30)

    /** Updated by [ZoneMonitor] while the VPN runs. */
    var inside by mutableStateOf(true)
        private set

    /** True when filtering should happen right now. */
    val blocking: Boolean get() = VpnState.running && inside

    fun hasLocationAccess(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time"; without it Android stops location updates while the app isn't open. */
    fun hasBackgroundAccess(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun update(ctx: Context, location: Location?) {
        val wasInside = inside
        inside = isInside(ctx, location)
        if (inside && !wasInside) AppBlockerService.recheckForeground()
    }

    private fun isInside(ctx: Context, location: Location?): Boolean {
        val zone = Prefs.zone(ctx) ?: return true
        if (location == null || !hasLocationAccess(ctx) || isMock(location)) return true
        val age = SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000
        if (age > STALE_MS) return true
        val d = FloatArray(1)
        Location.distanceBetween(zone.lat, zone.lon, location.latitude, location.longitude, d)
        // Outside only if even the nearest point of the fix's accuracy circle is outside the zone.
        return d[0] - location.accuracy <= zone.radius
    }

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider

    /** One fresh fix for setting the zone (last known fix before API 30). Null if unavailable. */
    fun currentLocation(ctx: Context, callback: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = bestProvider(lm) ?: return callback(null)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) { callback(it) }
            } else {
                callback(lm.getLastKnownLocation(provider))
            }
        } catch (_: SecurityException) {
            callback(null)
        }
    }

    /** The provider to ask: fused (GPS + Wi-Fi + cell) when the platform has it, else network. */
    fun bestProvider(lm: LocationManager): String? = when {
        Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }
}

/** Follows the phone's location while the VPN runs and keeps [Zone.inside] up to date. */
class ZoneMonitor(private val ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var last: Location? = null
    private var listening = false

    // Re-evaluates regularly so a stale fix (location turned off, updates stopped) counts as inside.
    private val recheck = object : Runnable {
        override fun run() {
            Zone.update(ctx, last)
            handler.postDelayed(this, RECHECK_MS)
        }
    }

    fun start() {
        stop()
        if (Zone.hasLocationAccess(ctx)) {
            try {
                Zone.bestProvider(lm)?.let {
                    lm.requestLocationUpdates(it, INTERVAL_MS, 0f, this, Looper.getMainLooper())
                }
                // Free: also take fixes other apps ask for.
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0f, this, Looper.getMainLooper())
                listening = true
                last = lm.allProviders.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
            } catch (_: SecurityException) {
            }
        }
        Zone.update(ctx, last)
        handler.postDelayed(recheck, RECHECK_MS)
    }

    fun stop() {
        handler.removeCallbacks(recheck)
        if (listening) {
            lm.removeUpdates(this)
            listening = false
        }
    }

    override fun onLocationChanged(location: Location) {
        // Ignore fixes older than the one we have (the passive provider can replay old ones).
        if ((last?.elapsedRealtimeNanos ?: 0) > location.elapsedRealtimeNanos) return
        last = location
        Zone.update(ctx, location)
    }

    override fun onProviderDisabled(provider: String) {
        // Location switched off: no more fixes, so fail closed right away.
        if (provider != LocationManager.PASSIVE_PROVIDER) {
            last = null
            Zone.update(ctx, null)
        }
    }

    // Overridden because they were abstract before API 29.
    override fun onProviderEnabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private companion object {
        val INTERVAL_MS = TimeUnit.MINUTES.toMillis(1)
        val RECHECK_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
