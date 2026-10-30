package imehc.recoow.core.domain.location

import kotlinx.coroutines.flow.Flow

data class LocationFix(val coordinate: GeoCoordinate, val timestamp: Long, val accuracyMeters: Float,
    val altitude: Double? = null, val verticalAccuracy: Double? = null, val speed: Double? = null, val course: Double? = null)

sealed interface LocationObservation {
    data class Fix(val location: LocationFix) : LocationObservation
    data object PermissionRequired : LocationObservation
    data object ProviderUnavailable : LocationObservation
}

interface LocationSource {
    fun observe(intervalMillis: Long, minimumDistanceMeters: Float): Flow<LocationObservation>
}
