package imehc.recoow.core.domain.location

import kotlinx.coroutines.flow.Flow

data class LocationFix(val coordinate: GeoCoordinate, val timestamp: Long, val accuracyMeters: Float)

sealed interface LocationObservation {
    data class Fix(val location: LocationFix) : LocationObservation
    data object PermissionRequired : LocationObservation
    data object ProviderUnavailable : LocationObservation
}

interface LocationSource {
    fun observe(intervalMillis: Long, minimumDistanceMeters: Float): Flow<LocationObservation>
}
