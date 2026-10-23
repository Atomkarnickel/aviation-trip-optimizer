package com.atomkarnickel.overhead.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.atomkarnickel.overhead.domain.LatLon
import com.atomkarnickel.overhead.domain.LocationSource
import com.atomkarnickel.overhead.domain.Observer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Gerätestandort ohne Google-Play-Dienste (nur Android-LocationManager). */
interface DeviceLocation {
    fun hasPermission(): Boolean
    fun hasBackgroundPermission(): Boolean
    /** Null, wenn keine Berechtigung oder kein Standort ermittelbar. */
    suspend fun current(): SavedLocation?
}

class AndroidDeviceLocation(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) : DeviceLocation {
    private val manager get() = context.getSystemService(LocationManager::class.java)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    override fun hasPermission() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun hasBackgroundPermission() =
        hasPermission() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    @SuppressLint("MissingPermission")
    override suspend fun current(): SavedLocation? {
        if (!hasPermission()) return null
        val lm = manager ?: return null
        return try {
            val providers = lm.getProviders(true)
            val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { clock() - it.time < FRESH_MS }
                .minByOrNull { it.accuracy }
            (recent ?: withTimeoutOrNull(TIMEOUT_MS) { requestOnce(lm, providers) })?.let {
                SavedLocation(LatLon(it.latitude, it.longitude), clock())
            }
        } catch (e: SecurityException) {
            null // z. B. im Hintergrund ohne „Immer erlauben“
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(lm: LocationManager, providers: List<String>): Location? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
            ?: return null
        return suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                cont.invokeOnCancellation { signal.cancel() }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderDisabled(provider: String) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(null)
                    }
                }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
    }

    companion object {
        const val FRESH_MS = 5 * 60_000L
        const val TIMEOUT_MS = 12_000L
    }
}

/**
 * Wählt den Beobachterstandort: fester Standort → aktueller Gerätestandort → zuletzt gespeicherter Gerätestandort.
 * Im Hintergrund wird das Gerät nur mit „Immer erlauben“ gefragt.
 */
class LocationResolver(private val device: DeviceLocation, private val settings: SettingsRepository) {
    suspend fun resolve(current: Settings, foreground: Boolean): Observer? {
        if (current.locationMode == LocationMode.FIXED) {
            current.fixedLocation?.let { return Observer(it, LocationSource.FIXED, 0L) }
        }
        val mayAsk = if (foreground) device.hasPermission() else device.hasBackgroundPermission()
        if (mayAsk) {
            device.current()?.let { loc ->
                settings.update { it.copy(lastDeviceLocation = loc) }
                return Observer(loc.position, LocationSource.DEVICE, loc.timeMillis)
            }
        }
        return current.lastDeviceLocation?.let { Observer(it.position, LocationSource.SAVED, it.timeMillis) }
            ?: current.fixedLocation?.let { Observer(it, LocationSource.FIXED, 0L) }
    }
}
