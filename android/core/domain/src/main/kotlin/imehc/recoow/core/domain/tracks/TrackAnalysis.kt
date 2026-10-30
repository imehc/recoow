package imehc.recoow.core.domain.tracks

import imehc.recoow.core.domain.location.GeoCoordinate
import kotlin.math.*

/** WGS84 Vincenty inverse; the spherical fallback only covers non-convergent antipodal pairs. */
fun trackDistance(a: GeoCoordinate, b: GeoCoordinate): Double {
    if (a == b) return 0.0
    val f = 1 / 298.257223563; val minor = 6356752.314245; val major = 6378137.0
    val u1 = atan((1 - f) * tan(Math.toRadians(a.latitude))); val u2 = atan((1 - f) * tan(Math.toRadians(b.latitude)))
    val l = Math.toRadians(b.longitude - a.longitude); var lambda = l
    var sinSigma = 0.0; var cosSigma = 0.0; var sigma = 0.0; var cosSqAlpha = 0.0; var cos2 = 0.0
    var converged = false
    for (iteration in 0..199) {
        sinSigma = hypot(cos(u2) * sin(lambda), cos(u1) * sin(u2) - sin(u1) * cos(u2) * cos(lambda))
        if (sinSigma == 0.0) return 0.0
        cosSigma = sin(u1) * sin(u2) + cos(u1) * cos(u2) * cos(lambda); sigma = atan2(sinSigma, cosSigma)
        val sinAlpha = cos(u1) * cos(u2) * sin(lambda) / sinSigma; cosSqAlpha = 1 - sinAlpha * sinAlpha
        cos2 = if (cosSqAlpha < 1e-15) 0.0 else cosSigma - 2 * sin(u1) * sin(u2) / cosSqAlpha
        val c = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha)); val previous = lambda
        lambda = l + (1 - c) * f * sinAlpha * (sigma + c * sinSigma * (cos2 + c * cosSigma * (-1 + 2 * cos2 * cos2)))
        if (abs(lambda - previous) < 1e-12) { converged = true; break }
    }
    if (!converged) {
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(l / 2).pow(2)
        return 6371008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    val u = cosSqAlpha * (major * major - minor * minor) / (minor * minor)
    val aa = 1 + u / 16384 * (4096 + u * (-768 + u * (320 - 175 * u)))
    val bb = u / 1024 * (256 + u * (-128 + u * (74 - 47 * u)))
    val delta = bb * sinSigma * (cos2 + bb / 4 * (cosSigma * (-1 + 2 * cos2 * cos2) - bb / 6 * cos2 * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2 * cos2)))
    return minor * aa * (sigma - delta)
}

object TrackAnalysis {
    private data class Leg(val start: Long, val end: Long, val distance: Double, val speed: Double)
    /** Matches the iOS analyzer. Recording sessions are analyzed separately to preserve explicit pauses. */
    fun segments(points: List<TrackPoint>): List<TrackSegment> {
        val legs = points.filter { (it.accuracy ?: 0.0) <= 100 }.sortedBy { it.time }.zipWithNext().mapNotNull { (a, b) ->
            val seconds = (b.time - a.time) / 1000.0
            if (seconds <= 0) null else {
                val distance = trackDistance(a.coordinate, b.coordinate); val speed = b.speed ?: distance / seconds
                if (speed > 80) null else Leg(a.time, b.time, distance, speed)
            }
        }
        val built = mutableListOf<TrackSegment>()
        legs.forEachIndexed { index, leg ->
            val speeds = legs.subList(max(0, index - 2), min(legs.size, index + 3)).map { it.speed }.sorted()
            val speed = speeds[speeds.size / 2]
            val type = when {
                leg.distance < 8 && leg.end - leg.start >= 20_000 || speed < 0.5 -> MotionType.STATIONARY
                speed < 2.2 -> MotionType.WALKING; speed < 4.8 -> MotionType.RUNNING; speed < 8.5 -> MotionType.CYCLING
                speed < 14 -> MotionType.TRANSIT; else -> MotionType.DRIVING
            }
            val current = TrackSegment(start = leg.start, end = leg.end, distance = leg.distance, averageSpeed = null, maxSpeed = speed, motion = type)
            val last = built.lastOrNull()
            if (last != null && last.motion == type && leg.start - last.end <= 120_000) built[built.lastIndex] = merge(last, current) else built += current
        }
        val merged = mutableListOf<TrackSegment>()
        built.forEach { segment -> val last = merged.lastOrNull()
            if (last != null && segment.duration < 15 && segment.start - last.end <= 120_000) merged[merged.lastIndex] = merge(last, segment) else merged += segment
        }
        return merged.map { s -> val avg = if (s.distance > 0 && s.duration > 0) s.distance / s.duration else null
            s.copy(averageSpeed = avg, confidence = confidence(s.motion, avg)) }
    }
    private fun merge(a: TrackSegment, b: TrackSegment) = a.copy(end = b.end, distance = a.distance + b.distance,
        duration = max(0, (b.end - a.start) / 1000), maxSpeed = listOfNotNull(a.maxSpeed, b.maxSpeed).maxOrNull())
    private fun confidence(type: MotionType, speed: Double?) = if (speed == null) 0.2 else when (type) {
        MotionType.STATIONARY -> if (speed < 0.5) 0.85 else 0.45
        MotionType.WALKING -> if (speed in 0.6..1.8) 0.8 else 0.55
        MotionType.RUNNING -> if (speed in 2.4..4.5) 0.75 else 0.5
        MotionType.CYCLING -> if (speed in 4.0..8.0) 0.65 else 0.45
        MotionType.TRANSIT, MotionType.DRIVING -> 0.45; MotionType.UNKNOWN -> 0.2
    }
    fun manual(segment: TrackSegment, points: List<TrackPoint>, start: Long = segment.start, end: Long = segment.end): TrackSegment {
        require(start < end)
        val range = points.filter { it.time in start..end }; require(range.size > 1)
        val distance = range.zipWithNext().sumOf { (a, b) -> trackDistance(a.coordinate, b.coordinate) }; val duration = (end - start) / 1000
        return segment.copy(start = start, end = end, distance = distance, duration = duration,
            averageSpeed = if (distance > 0 && duration > 0) distance / duration else null, maxSpeed = range.mapNotNull { it.speed }.maxOrNull(), manual = true, confidence = 1.0)
    }
}

enum class TrackQuality { WEAK_ACCURACY, TIME_GAP, SPEED_SPIKE }
data class QualityOccurrence(val point: TrackPoint, val value: Double)
data class QualityIssue(val kind: TrackQuality, val count: Int, val occurrences: List<QualityOccurrence>)
data class TrackPlace(val start: Long, val end: Long, val coordinate: GeoCoordinate)
object TrackInsights {
    fun quality(points: List<TrackPoint>): List<QualityIssue> {
        val ordered = points.sortedBy { it.time }; val values = TrackQuality.entries.associateWith { mutableListOf<QualityOccurrence>() }
        ordered.filter { (it.accuracy ?: 0.0) > 100 }.forEach { values.getValue(TrackQuality.WEAK_ACCURACY) += QualityOccurrence(it, it.accuracy!!) }
        ordered.zipWithNext().forEach { (a, b) -> val delta = b.time - a.time
            if (delta > 120_000) values.getValue(TrackQuality.TIME_GAP) += QualityOccurrence(b, delta / 1000.0)
            if (delta > 0) { val speed = trackDistance(a.coordinate, b.coordinate) / (delta / 1000.0)
                if (speed > 80) values.getValue(TrackQuality.SPEED_SPIKE) += QualityOccurrence(b, speed) }
        }
        return values.filterValues { it.isNotEmpty() }.map { (kind, samples) -> QualityIssue(kind, samples.size, samples.take(8)) }
    }
    fun places(points: List<TrackPoint>): List<TrackPlace> {
        val ordered = points.sortedBy { it.time }; if (ordered.size < 3) return emptyList()
        val result = mutableListOf<TrackPlace>(); var start = 0
        fun append(end: Int) { val group = ordered.subList(start, end)
            if (group.size >= 3 && group.last().time - group.first().time >= 300_000) result += TrackPlace(group.first().time, group.last().time,
                GeoCoordinate(group.map { it.coordinate.latitude }.average(), group.map { it.coordinate.longitude }.average())) }
        for (i in 1 until ordered.size) if (trackDistance(ordered[start].coordinate, ordered[i].coordinate) > 80) { append(i); start = i }
        append(ordered.size); return result.take(6)
    }
}
