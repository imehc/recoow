package imehc.recoow.feature.tracks

import org.junit.Assert.*
import org.junit.Test

class TrackMapLoadingTest {
    @Test fun styleReadyStillWaitsForRenderedTiles() {
        val state = TrackMapLoading().styleLoaded()
        assertTrue(state.loading)
        assertFalse(state.rendered)
        assertFalse(state.frameRendered().loading)
    }

    @Test fun slowMapKeepsTheOnlineStyleAndAcceptsLateTiles() {
        val state = TrackMapLoading().styleLoaded().timedOut()
        assertTrue(state.slow)
        assertFalse(state.routeOnly)
        val recovered = state.frameRendered()
        assertTrue(recovered.rendered)
        assertFalse(recovered.slow)
        assertNull(recovered.problem)
        assertEquals(recovered, recovered.timedOut())
    }

    @Test fun dnsFailureShowsRouteWithoutPretendingTheBasemapRecovered() {
        val failed = TrackMapLoading().failed("Unable to resolve host \"tiles.openfreemap.org\": No address associated with hostname")
        assertEquals(TrackMapProblem.DNS, failed.problem)
        assertTrue(failed.routeOnly)
        assertFalse(failed.loading)
        assertEquals(failed, failed.frameRendered())
        assertEquals(failed, failed.timedOut())
        // 新的加载尝试不会继承此前仅保存路线的状态。
        val retry = TrackMapLoading()
        assertTrue(retry.loading)
        assertFalse(retry.routeOnly)
    }

    @Test fun failureAfterStyleReadyKeepsAlreadyLoadedMapContent() {
        val state = TrackMapLoading().styleLoaded().failed("Failed to connect to map service")
        assertFalse(state.routeOnly)
        assertEquals(TrackMapProblem.NETWORK, state.problem)
        assertNull(state.frameRendered().problem)
    }
}
