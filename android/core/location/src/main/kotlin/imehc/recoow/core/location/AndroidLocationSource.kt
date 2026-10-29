package imehc.recoow.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.location.LocationFix
import imehc.recoow.core.domain.location.LocationObservation
import imehc.recoow.core.domain.location.LocationSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Uses Android providers; callers own permission UX and foreground-service lifetime. */
class AndroidLocationSource(context: Context) : LocationSource {
    private val context = context.applicationContext
    @SuppressLint("MissingPermission") // Both permissions are checked immediately before registration.
    override fun observe(intervalMillis: Long, minimumDistanceMeters: Float) = callbackFlow {
        require(intervalMillis >= 0 && minimumDistanceMeters.isFinite() && minimumDistanceMeters >= 0)
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            trySend(LocationObservation.PermissionRequired)
            close()
            return@callbackFlow
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
            .filter { it in manager.allProviders }
        if (providers.isEmpty()) {
            trySend(LocationObservation.ProviderUnavailable)
            close()
            return@callbackFlow
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
                    !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return
                trySend(LocationObservation.Fix(LocationFix(GeoCoordinate(location.latitude, location.longitude),
                    location.time, location.accuracy)))
            }
            override fun onProviderDisabled(provider: String) {
                if (providers.none { manager.isProviderEnabled(it) }) trySend(LocationObservation.ProviderUnavailable)
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Required for API 26 compatibility")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            for (provider in providers) manager.requestLocationUpdates(provider, intervalMillis, minimumDistanceMeters, listener, Looper.getMainLooper())
            if (providers.none { manager.isProviderEnabled(it) }) trySend(LocationObservation.ProviderUnavailable)
        } catch (_: SecurityException) {
            manager.removeUpdates(listener)
            trySend(LocationObservation.PermissionRequired)
            close()
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}
