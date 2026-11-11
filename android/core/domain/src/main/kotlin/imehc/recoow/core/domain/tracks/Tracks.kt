package imehc.recoow.core.domain.tracks

import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.location.LocationFix
import imehc.recoow.core.domain.navigation.ToolRoute
import java.io.Serializable
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class TrackStatus { RECORDING, PAUSED, FINISHED }
enum class MotionType { UNKNOWN, STATIONARY, WALKING, RUNNING, CYCLING, TRANSIT, DRIVING }
data class Track(val id: String, val revision: String, val name: String, val note: String?, val deviceId: String,
    val startedAt: Long, val endedAt: Long?, val accuracy: Int, val status: TrackStatus, val distance: Double,
    val duration: Long, val averageSpeed: Double?, val maxSpeed: Double?, val pointCount: Int = 0)
data class TrackPoint(val id: String = UUID.randomUUID().toString(), val coordinate: GeoCoordinate, val time: Long,
    val accuracy: Double? = null, val altitude: Double? = null, val verticalAccuracy: Double? = null,
    val speed: Double? = null, val course: Double? = null) {
    companion object { fun from(fix: LocationFix) = TrackPoint(coordinate = fix.coordinate, time = fix.timestamp,
        accuracy = fix.accuracyMeters.toDouble().takeIf { it >= 0 }, altitude = fix.altitude,
        verticalAccuracy = fix.verticalAccuracy, speed = fix.speed, course = fix.course) }
}
data class TrackSegment(val id: String = UUID.randomUUID().toString(), val start: Long, val end: Long,
    val distance: Double, val duration: Long = ((end - start) / 1000).coerceAtLeast(0), val averageSpeed: Double?,
    val maxSpeed: Double?, val motion: MotionType, val manual: Boolean = false, val confidence: Double = 1.0)
data class TrackRelated(val id: String, val route: ToolRoute, val title: String, val time: Long)
data class TrackDetail(val track: Track? = null, val points: List<TrackPoint> = emptyList(),
    val segments: List<TrackSegment> = emptyList(), val revision: String = "", val related: List<TrackRelated> = emptyList())
data class TrackDraft(val id: String, val revision: String, val name: String, val note: String) : Serializable
sealed interface SegmentEdit {
    data class Type(val motion: MotionType) : SegmentEdit
    data class Split(val at: Long) : SegmentEdit
    data class Bounds(val start: Long, val end: Long) : SegmentEdit
    data class Merge(val previous: Boolean) : SegmentEdit
}
/** 记录租约在数据库被替换、远端已完成或记录被删除时失效。 */
data class TrackSession(val track: Track, val token: Long, val start: Long)
class TrackSessionExpiredException : IllegalStateException("Recording dataset or track changed")
interface TracksRepository {
    val tracks: Flow<List<Track>>
    fun detail(id: String): Flow<TrackDetail>
    suspend fun recover(): Track?
    suspend fun begin(name: String, accuracy: Int, resumeId: String? = null): TrackSession
    suspend fun checkpoint(session: TrackSession, points: List<TrackPoint>, duration: Long, status: TrackStatus = TrackStatus.RECORDING): Track
    suspend fun finishPaused(id: String): Track
    suspend fun edit(draft: TrackDraft)
    suspend fun editSegment(trackId: String, expected: String, segmentId: String, edit: SegmentEdit)
    suspend fun delete(id: String, expected: String)
    suspend fun ensureSegments(id: String)
}
