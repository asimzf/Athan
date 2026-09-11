package com.asimzf.salaahalarm.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * A single coarse fix is all prayer times need — a kilometre of error moves them by well
 * under a minute — so this uses the platform LocationManager rather than pulling in Play
 * Services.
 */
class LocationProvider(private val context: Context) {

    data class Fix(val latitude: Double, val longitude: Double, val label: String)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun currentFix(): Fix? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        val location = lastKnown(manager) ?: requestFresh(manager) ?: return null
        return Fix(location.latitude, location.longitude, describe(location))
    }

    private fun lastKnown(manager: LocationManager): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private suspend fun requestFresh(manager: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(
                    provider,
                    signal,
                    context.mainExecutor,
                ) { location -> continuation.resume(location) }
            }.onFailure { continuation.resume(null) }
        }
    }

    /** Best-effort city name for display only; the calculation uses the coordinates. */
    private fun describe(location: Location): String {
        val fallback = String.format(
            Locale.US,
            "%.3f, %.3f",
            location.latitude,
            location.longitude,
        )
        if (!Geocoder.isPresent()) return fallback
        return runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
            val address = results?.firstOrNull() ?: return fallback
            listOfNotNull(address.locality ?: address.subAdminArea, address.countryName)
                .joinToString(", ")
                .ifBlank { fallback }
        }.getOrDefault(fallback)
    }
}
