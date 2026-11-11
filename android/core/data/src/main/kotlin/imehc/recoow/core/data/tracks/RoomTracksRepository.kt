package imehc.recoow.core.data.tracks

import androidx.room.withTransaction
import imehc.recoow.core.data.journal.*
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.records.StaleRecordException
import imehc.recoow.core.domain.tracks.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomTracksRepository(private val host: DatabaseHost, private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis) : TracksRepository {
    private val storage = JournalStorage(host, deviceId, now)
    override val tracks = host.observe { db -> combine(db.records().observe("tracks"), db.records().observeTrackCounts()) { rows, counts ->
        val sizes = counts.associate { it.trackId to it.count }
        rows.filter { it.fields().live }.map { track(it).copy(pointCount = sizes[it.entityId] ?: 0) }.sortedByDescending { it.startedAt }
    } }.flowOn(Dispatchers.Default)
    override fun detail(id: String) = host.observe { db -> combine(db.records().observeTrack(id), db.records().observeTables(listOf("bills", "diary_entries"))) { rows, related ->
        val decoded = decode(rows)
        val range = decoded.points.firstOrNull()?.time?.let { it..decoded.points.last().time }
        decoded.copy(related = if (range == null) emptyList() else related.filter { it.fields().live && it.fields().long("occurred_at") in range }.map {
            TrackRelated(it.entityId, if (it.tableName == "bills") ToolRoute.BILLS else ToolRoute.DIARY, it.fields().text("title").orEmpty(), it.fields().long("occurred_at")!!)
        }.sortedByDescending { it.time })
    } }.flowOn(Dispatchers.Default)
    private fun track(row: EntitySnapshot): Track = row.fields().let { f -> Track(row.entityId, revision(listOf(row)), f.text("name")!!,
        f.text("note"), f.text("device_id")!!, f.long("started_at")!!, f.long("ended_at"), f.long("desired_accuracy_m")!!.toInt(),
        TrackStatus.entries.firstOrNull { it.name.equals(f.text("status"), true) } ?: if (f.long("ended_at") == null) TrackStatus.PAUSED else TrackStatus.FINISHED,
        f.double("distance_m")!!, f.long("duration_s")!!, f.double("avg_speed_mps"), f.double("max_speed_mps")) }
    private fun decode(rows: List<EntitySnapshot>): TrackDetail {
        val live = rows.filter { it.fields().live }
        return TrackDetail(live.firstOrNull { it.tableName == "tracks" }?.let(::track),
            live.filter { it.tableName == "track_points" }.map { r -> val f = r.fields(); TrackPoint(r.entityId, GeoCoordinate(f.double("latitude")!!, f.double("longitude")!!),
                f.long("timestamp_ms")!!, f.double("horizontal_acc"), f.double("altitude"), f.double("vertical_acc"), f.double("speed_mps"), f.double("course_deg")) }.sortedWith(compareBy<TrackPoint> { it.time }.thenBy { it.id }),
            live.filter { it.tableName == "track_segments" }.map { r -> val f = r.fields(); TrackSegment(r.entityId, f.long("started_at")!!, f.long("ended_at")!!,
                f.double("distance_m")!!, f.long("duration_s")!!, f.double("avg_speed_mps"), f.double("max_speed_mps"),
                MotionType.entries.firstOrNull { it.name.equals(f.text("motion_type"), true) } ?: MotionType.UNKNOWN, f.text("source") == "manual", f.double("confidence")!!) }.sortedWith(compareBy<TrackSegment> { it.start }.thenBy { it.id }), revision(rows))
    }
    override suspend fun recover(): Track? = host.access { db -> db.withTransaction {
        val own = db.records().snapshots("tracks").filter { it.fields().live && it.fields().text("device_id") == deviceId }.map(::track)
        val interrupted = own.filter { it.status == TrackStatus.RECORDING }
        storage.writer.saveInTransaction(db, interrupted.map { storage.change("tracks", it.id, buildJsonObject { put("status", "paused") }) })
        (interrupted + own.filter { it.status == TrackStatus.PAUSED }).maxByOrNull { it.startedAt }?.let { track(db.records().snapshot("tracks", it.id)!!) }
    } }
    override suspend fun begin(name: String, accuracy: Int, resumeId: String?): TrackSession {
        val token = host.sessionToken()
        return host.access(token) { db -> db.withTransaction {
            require(accuracy in listOf(5, 10, 100))
            val own = db.records().snapshots("tracks").filter { it.fields().live && it.fields().text("device_id") == deviceId }.map(::track)
            require(own.none { it.status == TrackStatus.RECORDING })
            val paused = own.filter { it.status == TrackStatus.PAUSED }
            val id = resumeId ?: UUID.randomUUID().toString()
            val old = if (resumeId == null) null else paused.firstOrNull { it.id == resumeId }
            require(if (resumeId == null) paused.isEmpty() else old != null)
            val last = if (old == null) null else decode(db.records().trackRows(id)).points.lastOrNull()?.time
            val start = maxOf(now(), (last ?: Long.MIN_VALUE) + 1)
            storage.writer.saveInTransaction(db, listOf(storage.change("tracks", id, buildJsonObject {
                put("status", "recording"); put("ended_at", JsonNull)
                if (old == null) {
                    require(name.isNotBlank()); put("name", name.trim()); put("note", JsonNull); put("started_at", start); put("desired_accuracy_m", accuracy)
                    put("distance_m", 0.0); put("duration_s", 0); put("avg_speed_mps", JsonNull); put("max_speed_mps", JsonNull)
                }
            })))
            TrackSession(track(db.records().snapshot("tracks", id)!!), token, start)
        } }
    }
    override suspend fun checkpoint(session: TrackSession, points: List<TrackPoint>, duration: Long, status: TrackStatus): Track {
      try { return host.access(session.token) { db -> db.withTransaction {
        val id = session.track.id; val rows = db.records().trackRows(id); val before = decode(rows); val current = before.track ?: throw TrackSessionExpiredException()
        if (current.deviceId != deviceId || current.status != TrackStatus.RECORDING || before.segments.any { it.manual && it.end >= session.start }) throw TrackSessionExpiredException()
        require(duration >= current.duration)
        var last = before.points.lastOrNull()?.time ?: Long.MIN_VALUE
        val accepted = points.filter { p ->
            require(p.time >= session.start && p.time <= now() + 60_000)
            require(listOfNotNull(p.accuracy, p.altitude, p.verticalAccuracy, p.speed, p.course).all { it.isFinite() })
            if (p.time > last) { last = p.time; true } else false
        }
        val all = before.points + accepted
        val automatic = TrackAnalysis.segments(all.filter { it.time >= session.start }).map { s ->
            s.copy(id = UUID.nameUUIDFromBytes("$id:${session.start}:${s.start}".toByteArray()).toString()) }
        val previous = before.segments.filter { it.start < session.start || it.manual }
        val segments = previous + automatic
        val changes = accepted.map { p -> storage.change("track_points", p.id, buildJsonObject {
            put("track_id", id); put("timestamp_ms", p.time); put("latitude", p.coordinate.latitude); put("longitude", p.coordinate.longitude)
            put("horizontal_acc", p.accuracy.json()); put("altitude", p.altitude.json()); put("vertical_acc", p.verticalAccuracy.json()); put("speed_mps", p.speed.json()); put("course_deg", p.course.json())
        }) }.toMutableList()
        before.segments.filter { it !in previous && automatic.none { a -> a.id == it.id } }.forEach { changes += storage.change("track_segments", it.id, deleted = true) }
        automatic.filter { it !in before.segments }.forEach { changes += segmentChange(id, it) }
        val distance = segments.sumOf { it.distance }; val movingDuration = segments.sumOf { it.duration }
        changes += storage.change("tracks", id, buildJsonObject {
            put("status", status.name.lowercase()); put("ended_at", if (status == TrackStatus.FINISHED) now().json() else JsonNull)
            put("distance_m", distance); put("duration_s", duration); put("avg_speed_mps", (if (distance > 0 && movingDuration > 0) distance / movingDuration else null).json())
            put("max_speed_mps", segments.mapNotNull { it.maxSpeed }.maxOrNull().json())
        })
        storage.writer.saveInTransaction(db, changes)
        track(db.records().snapshot("tracks", id)!!).copy(pointCount = all.size)
      } } } catch (e: IllegalStateException) {
          if (e.message == "Stale database session") throw TrackSessionExpiredException()
          throw e
      }
    }
    override suspend fun finishPaused(id: String): Track = host.access { db -> db.withTransaction {
        val row = db.records().snapshot("tracks", id)
        if (row == null || !row.fields().live) throw TrackSessionExpiredException()
        val current = track(row)
        require(current.status == TrackStatus.PAUSED && current.deviceId == deviceId)
        storage.writer.saveInTransaction(db, listOf(storage.change("tracks", id, buildJsonObject { put("status", "finished"); put("ended_at", now()) })))
        track(db.records().snapshot("tracks", id)!!)
    } }
    override suspend fun edit(draft: TrackDraft) = host.access { db -> db.withTransaction {
        require(draft.name.isNotBlank()); storage.check(db.records().snapshot("tracks", draft.id), draft.revision)
        storage.writer.saveInTransaction(db, listOf(storage.change("tracks", draft.id, buildJsonObject { put("name", draft.name.trim()); put("note", draft.note.normalized().json()) })))
    } }
    override suspend fun ensureSegments(id: String) = host.access { db -> db.withTransaction {
        val detail = decode(db.records().trackRows(id))
        if (detail.track?.status == TrackStatus.FINISHED && detail.segments.isEmpty()) {
            storage.writer.saveInTransaction(db, TrackAnalysis.segments(detail.points).map { segmentChange(id, it) })
        }
    } }
    override suspend fun editSegment(trackId: String, expected: String, segmentId: String, edit: SegmentEdit) = host.access { db -> db.withTransaction {
        val rows = db.records().trackRows(trackId); val detail = decode(rows)
        if (detail.revision != expected || detail.track == null) throw StaleRecordException()
        require(requireNotNull(detail.track).status == TrackStatus.FINISHED)
        val segments = detail.segments; val index = segments.indexOfFirst { it.id == segmentId }; require(index >= 0)
        val selected = segments[index]; val changes = mutableListOf<imehc.recoow.core.domain.records.RecordChange>()
        fun update(s: TrackSegment, start: Long = s.start, end: Long = s.end) { changes += segmentChange(trackId, TrackAnalysis.manual(s, detail.points, start, end)) }
        when (edit) {
            is SegmentEdit.Type -> changes += segmentChange(trackId, selected.copy(motion = edit.motion, manual = true, confidence = 1.0))
            is SegmentEdit.Split -> {
                require(edit.at > selected.start && edit.at < selected.end && detail.points.any { it.time == edit.at })
                update(selected, end = edit.at); update(selected.copy(id = UUID.randomUUID().toString()), start = edit.at)
            }
            is SegmentEdit.Merge -> {
                val neighbor = segments.getOrNull(index + if (edit.previous) -1 else 1); requireNotNull(neighbor)
                // 记录时显式留下的空档，不会被合并悄悄填平。
                require(if (edit.previous) neighbor.end == selected.start else selected.end == neighbor.start)
                update(selected, minOf(selected.start, neighbor.start), maxOf(selected.end, neighbor.end))
                changes += storage.change("track_segments", neighbor.id, deleted = true)
            }
            is SegmentEdit.Bounds -> {
                val previous = segments.getOrNull(index - 1); val next = segments.getOrNull(index + 1)
                require(edit.start < edit.end && detail.points.any { it.time == edit.start } && detail.points.any { it.time == edit.end })
                if (previous == null || previous.end != selected.start) require(edit.start == selected.start)
                else { require(edit.start > previous.start); if (edit.start != selected.start) update(previous, end = edit.start) }
                if (next == null || selected.end != next.start) require(edit.end == selected.end)
                else { require(edit.end < next.end); if (edit.end != selected.end) update(next, start = edit.end) }
                update(selected, edit.start, edit.end)
            }
        }
        storage.writer.saveInTransaction(db, changes)
    } }
    override suspend fun delete(id: String, expected: String) = host.access { db -> db.withTransaction {
        val rows = db.records().trackRows(id); val detail = decode(rows)
        if (expected != detail.revision || detail.track == null) throw StaleRecordException()
        require(requireNotNull(detail.track).status != TrackStatus.RECORDING)
        storage.writer.saveInTransaction(db, rows.filter { it.fields().live }.map { storage.change(it.tableName, it.entityId, deleted = true) })
    } }
    private fun segmentChange(trackId: String, s: TrackSegment) = storage.change("track_segments", s.id, buildJsonObject {
        put("track_id", trackId); put("started_at", s.start); put("ended_at", s.end); put("distance_m", s.distance); put("duration_s", s.duration)
        put("avg_speed_mps", s.averageSpeed.json()); put("max_speed_mps", s.maxSpeed.json()); put("motion_type", s.motion.name.lowercase())
        put("source", if (s.manual) "manual" else "auto"); put("confidence", s.confidence)
    })
}
