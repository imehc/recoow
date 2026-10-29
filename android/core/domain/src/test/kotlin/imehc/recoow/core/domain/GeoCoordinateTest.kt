package imehc.recoow.core.domain

import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.location.MapCoordinateSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GeoCoordinateTest {
    @Test fun displayCoordinateMatchesIOSWithoutChangingStoredWGS84() {
        val stored = GeoCoordinate(39.907, 116.397)
        val display = stored.forDisplay(MapCoordinateSystem.GCJ02)
        // CoordinateTransform.swift at e0a02fc, evaluated using Swift/Foundation.
        assertEquals(39.90840331035832, display.latitude, 1e-8)
        assertEquals(116.40324325105628, display.longitude, 1e-8)
        assertEquals(39.907, stored.latitude, 0.0)
        assertSame(stored, stored.forDisplay(MapCoordinateSystem.WGS84))
        val london = GeoCoordinate(51.5074, -0.1278)
        assertSame(london, london.forDisplay(MapCoordinateSystem.GCJ02))
    }
}
