package imehc.recoow.core.location

import imehc.recoow.core.domain.location.LocationFix
import imehc.recoow.core.domain.location.LocationObservation
import imehc.recoow.core.domain.location.LocationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

enum class LocationPreviewProblem { PERMISSION, PROVIDER }

data class LocationPreviewState(
    val fix: LocationFix? = null,
    val anchor: LocationFix? = null,
    val problem: LocationPreviewProblem? = null,
)

/** 只用于前台展示，该对象不会持有记录器或仓库。 */
class LocationPreview(private val source: LocationSource, private val cachedFix: () -> LocationFix? = { null }) {
    val states get() = flow {
        var state = LocationPreviewState()
        var hasLiveFix = false
        emit(state)
        try {
            cachedFix()?.let { fix ->
                state = state.copy(fix = fix, anchor = fix)
                emit(state)
            }
            source.observe(5_000, 5f).collect { observation ->
                state = when (observation) {
                    is LocationObservation.Fix -> {
                        val fix = observation.location
                        if (fix.timestamp < (state.fix?.timestamp ?: Long.MIN_VALUE)) return@collect
                        // 首次拿到实时定位后居中，之后允许用户自由浏览地图。
                        val anchor = if (hasLiveFix) state.anchor else fix
                        hasLiveFix = true
                        LocationPreviewState(fix, anchor)
                    }
                    LocationObservation.PermissionRequired -> {
                        hasLiveFix = false
                        LocationPreviewState(problem = LocationPreviewProblem.PERMISSION)
                    }
                    LocationObservation.ProviderUnavailable -> state.copy(problem = LocationPreviewProblem.PROVIDER)
                }
                emit(state)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(state.copy(problem = LocationPreviewProblem.PROVIDER))
        }
    }
}
