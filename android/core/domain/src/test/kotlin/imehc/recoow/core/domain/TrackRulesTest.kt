package imehc.recoow.core.domain

import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.tracks.*
import org.junit.Assert.*
import org.junit.Test

class TrackRulesTest {
    private data class Expected(val start: Long, val end: Long, val distance: Double, val duration: Long, val average: Double, val maximum: Double, val motion: MotionType, val confidence: Double)
    // 用仓库中真实的 Swift TrackSegmentAnalyzer 与 CoreLocation 生成一次。
    // 不同 WGS84 实现的差异可控制在每段 5 厘米、速度 0.001 米/秒以内。
    @Test fun segmentationMatchesSwiftForSmoothingFilteringGapsAndThresholds() {
        val expected = listOf(
            listOf(
                Expected(1700000000000L, 1700000070000L, 66.69198975456239, 70L, 0.9527427107794626, 1.2, MotionType.WALKING, 0.8),
                Expected(1700000070000L, 1700000150000L, 76.21941686274386, 80L, 0.9527427107842982, 3.2, MotionType.RUNNING, 0.5),
                Expected(1700000150000L, 1700000240000L, 85.74684397092531, 90L, 0.952742710788059, 10.0, MotionType.TRANSIT, 0.45)
            ),
            listOf(
                Expected(1700000000000L, 1700000070000L, 66.69198975456239, 70L, 0.9527427107794626, 1.2, MotionType.WALKING, 0.8),
                Expected(1700000070000L, 1700000150000L, 66.69198975456239, 80L, 0.8336498719320298, 3.2, MotionType.RUNNING, 0.5),
                Expected(1700000150000L, 1700000240000L, 85.74684397092531, 90L, 0.952742710788059, 10.0, MotionType.TRANSIT, 0.45)
            ),
            listOf(
                Expected(1700000000000L, 1700000540000L, 228.65825058823145, 540L, 0.4234412047930212, 0.9527427108181463, MotionType.WALKING, 0.55)
            ),
            listOf(
                Expected(1700000000000L, 1700000015000L, 28.58228132319046, 15L, 1.9054854215460308, 0.1, MotionType.STATIONARY, 0.45),
                Expected(1700000015000L, 1700000035000L, 38.10970843137193, 20L, 1.9054854215685963, 1.2, MotionType.WALKING, 0.55),
                Expected(1700000035000L, 1700000055000L, 38.10970843137193, 20L, 1.9054854215685963, 3.2, MotionType.RUNNING, 0.5),
                Expected(1700000055000L, 1700000075000L, 38.10970843137193, 20L, 1.9054854215685963, 6.0, MotionType.CYCLING, 0.45),
                Expected(1700000075000L, 1700000095000L, 38.10970843272585, 20L, 1.9054854216362926, 10.0, MotionType.TRANSIT, 0.45),
                Expected(1700000095000L, 1700000120000L, 47.63713553819946, 25L, 1.9054854215279784, 20.0, MotionType.DRIVING, 0.45)
            )
        )
        expected.forEachIndexed { scenario, wanted ->
            val points = (0..24).map { index ->
                val step = if (scenario == 3) 5000 else 10000
                val time = 1700000000000L + index * step + if (scenario == 2 && index > 12) 300000 else 0
                val velocity = if (scenario == 2) null else if (scenario == 3) listOf(0.1, 1.2, 3.2, 6.0, 10.0, 20.0)[(index / 4).coerceAtMost(5)] else if (index < 8) 1.2 else if (index < 16) 3.2 else 10.0
                TrackPoint(coordinate = GeoCoordinate(31.23, 121.47 + index * 0.0001), time = time,
                    accuracy = if (scenario == 1 && index == 5) 200.0 else 5.0, speed = if (scenario == 1 && index == 12) 90.0 else velocity)
            }
            val actual = TrackAnalysis.segments(points); assertEquals(wanted.size, actual.size)
            wanted.zip(actual).forEach { (e, a) ->
                assertEquals(e.start, a.start); assertEquals(e.end, a.end); assertEquals(e.duration, a.duration)
                assertEquals(e.motion, a.motion); assertEquals(e.distance, a.distance, 0.05)
                assertEquals(e.average, a.averageSpeed!!, 0.001); assertEquals(e.maximum, a.maxSpeed!!, 0.001); assertEquals(e.confidence, a.confidence, 0.0001)
            }
        }
    }
    @Test fun stationaryPlacesQualityCountsAndManualMetricsUseOriginalPoints() {
        val points = (0..10).map { TrackPoint(coordinate = GeoCoordinate(31.23, 121.47), time = 1700000000000L + it * 60000L, accuracy = 200.0, speed = 0.0) }
        assertEquals(0.0, trackDistance(points.first().coordinate, points.last().coordinate), 0.0)
        assertEquals(1, TrackInsights.places(points).size)
        val quality = TrackInsights.quality(points).single(); assertEquals(11, quality.count); assertEquals(8, quality.occurrences.size)
        val far = TrackPoint(coordinate = GeoCoordinate(40.0, -70.0), time = points.last().time + 301000)
        val issues = TrackInsights.quality(points + far); assertEquals(3, issues.size)
        assertTrue(TrackAnalysis.segments(points).isEmpty())
        val segment = TrackSegment(start = points.first().time, end = points.last().time, distance = 0.0, averageSpeed = null, maxSpeed = 0.0, motion = MotionType.STATIONARY)
        val manual = TrackAnalysis.manual(segment, points); assertTrue(manual.manual); assertEquals(600L, manual.duration); assertNull(manual.averageSpeed)
        assertTrue(trackDistance(GeoCoordinate(0.0, 179.999), GeoCoordinate(0.0, -179.999)) in 222.0..223.0)
        assertTrue(trackDistance(GeoCoordinate(0.0, 0.0), GeoCoordinate(0.0, 180.0)).isFinite())
    }
}
