package imehc.recoow.core.location

import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.location.LocationFix
import imehc.recoow.core.domain.location.LocationObservation
import imehc.recoow.core.domain.location.LocationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationPreviewTest {
    private fun fix(time: Long) = LocationFix(GeoCoordinate(39.9 + time * 0.00001, 116.4), time, 10f)
    private fun source(vararg observations: LocationObservation) = object : LocationSource {
        override fun observe(intervalMillis: Long, minimumDistanceMeters: Float) = flowOf(*observations)
    }

    @Test fun cacheDisplaysImmediatelyAndFirstLiveFixCentersOnce() = runTest {
        val cached = fix(10)
        val first = fix(20)
        val moved = fix(30)
        val states = LocationPreview(source(LocationObservation.Fix(first), LocationObservation.Fix(moved))) { cached }.states.toList()
        assertEquals(LocationPreviewState(), states[0])
        assertEquals(LocationPreviewState(cached, cached), states[1])
        assertEquals(LocationPreviewState(first, first), states[2])
        assertEquals(LocationPreviewState(moved, first), states[3])
    }

    @Test fun outdatedLocationsCannotReplaceNewerCacheOrLiveFix() = runTest {
        val cached = fix(20)
        val newest = fix(40)
        val states = LocationPreview(source(LocationObservation.Fix(fix(10)), LocationObservation.Fix(newest),
            LocationObservation.Fix(fix(30)))) { cached }.states.toList()
        assertEquals(listOf(LocationPreviewState(), LocationPreviewState(cached, cached), LocationPreviewState(newest, newest)), states)
    }

    @Test fun deniedPermissionClearsLocationAndRecoveryCentersAgain() = runTest {
        val first = fix(10)
        val recovered = fix(20)
        val states = LocationPreview(source(LocationObservation.Fix(first), LocationObservation.PermissionRequired,
            LocationObservation.Fix(recovered))).states.toList()
        assertEquals(LocationPreviewState(problem = LocationPreviewProblem.PERMISSION), states[2])
        assertEquals(LocationPreviewState(recovered, recovered), states.last())
    }

    @Test fun unavailableProviderKeepsLastPositionAndRecoveryClearsProblem() = runTest {
        val first = fix(10)
        val recovered = fix(20)
        val states = LocationPreview(source(LocationObservation.Fix(first), LocationObservation.ProviderUnavailable,
            LocationObservation.Fix(recovered))).states.toList()
        assertEquals(LocationPreviewState(first, first, LocationPreviewProblem.PROVIDER), states[2])
        assertEquals(LocationPreviewState(recovered, first), states.last())
    }

    @Test fun providerExceptionShowsProblemAndPreservesCachedPosition() = runTest {
        val cached = fix(10)
        val failing = object : LocationSource {
            override fun observe(intervalMillis: Long, minimumDistanceMeters: Float): Flow<LocationObservation> = flow {
                throw IllegalStateException("Provider disconnected")
            }
        }
        val states = LocationPreview(failing) { cached }.states.toList()
        assertEquals(LocationPreviewState(cached, cached, LocationPreviewProblem.PROVIDER), states.last())
    }

    @Test fun cancellingAndResubscribingReleasesProviderAndStartsNewSession() = runTest {
        var sessions = 0
        var activeSubscriptions = 0
        val streaming = object : LocationSource {
            override fun observe(intervalMillis: Long, minimumDistanceMeters: Float): Flow<LocationObservation> = flow {
                assertEquals(5_000L, intervalMillis)
                assertEquals(5f, minimumDistanceMeters)
                activeSubscriptions++
                sessions++
                try {
                    emit(LocationObservation.Fix(fix(sessions.toLong())))
                    awaitCancellation()
                } finally { activeSubscriptions-- }
            }
        }
        val preview = LocationPreview(streaming)
        val states = mutableListOf<LocationPreviewState>()
        val first = backgroundScope.launch { preview.states.collect { states += it } }
        runCurrent()
        assertEquals(1, activeSubscriptions)
        assertEquals(fix(1), states.last().anchor)
        first.cancel()
        runCurrent()
        assertEquals(0, activeSubscriptions)
        assertNull(states.last().problem)
        val second = backgroundScope.launch { preview.states.collect { states += it } }
        runCurrent()
        assertEquals(2, sessions)
        assertEquals(1, activeSubscriptions)
        assertEquals(fix(2), states.last().anchor)
        second.cancel()
        runCurrent()
        assertEquals(0, activeSubscriptions)
    }
}
