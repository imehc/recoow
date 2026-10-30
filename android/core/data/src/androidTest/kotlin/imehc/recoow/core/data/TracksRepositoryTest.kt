package imehc.recoow.core.data

import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.*
import imehc.recoow.core.data.tracks.RoomTracksRepository
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.records.*
import imehc.recoow.core.domain.tracks.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class TracksRepositoryTest {
    private lateinit var root: File; private lateinit var host: DatabaseHost; private lateinit var repository: RoomTracksRepository
    private var clock = 1700000000000L
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "tracks-${UUID.randomUUID()}").apply { mkdirs() }; val live = File(root, "live")
        host = DatabaseHost(context, live, LocalRollbackFiles(live, File(root, "rollback"), object : BackupPreferences {
            override suspend fun capture() = "{}"; override suspend fun restore(json: String) = Unit
        }, "test")); repository = RoomTracksRepository(host, "test") { clock }
    }
    @After fun cleanup() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private fun points(start: Long, count: Int = 10) = (0 until count).map { TrackPoint(coordinate = GeoCoordinate(31.23, 121.47 + it * 0.0001), time = start + it * 10_000, accuracy = 5.0, speed = 1.0) }
    @Test fun recordingLeaseCheckpointsPausesRecoversAndDoesNotBridgeSessions() = runBlocking {
        val first = repository.begin("First", 10)
        try { repository.begin("Duplicate", 5); fail() } catch (_: IllegalArgumentException) { }
        clock += 100_000
        repository.checkpoint(first, points(first.start), 100, TrackStatus.PAUSED)
        val paused = repository.recover()!!; assertEquals(100L, paused.duration); val original = repository.detail(paused.id).first()
        clock += 3_600_000
        val resumed = repository.begin("Ignored", 100, paused.id); assertEquals(10, resumed.track.accuracy)
        clock += 100_000
        repository.checkpoint(resumed, points(resumed.start), 200)
        host.close()
        val restored = repository.recover()!!; assertEquals(TrackStatus.PAUSED, restored.status); assertEquals(200L, restored.duration)
        val d = repository.detail(restored.id).first(); assertEquals(20, d.points.size); assertEquals(2, d.segments.size)
        assertEquals(original.segments.sumOf { it.distance } * 2, d.segments.sumOf { it.distance }, 0.001)
        try { repository.checkpoint(resumed, emptyList(), 200); fail() } catch (_: TrackSessionExpiredException) { }
        repository.finishPaused(restored.id); assertNull(repository.recover())
    }
    @Test fun editsSplitBoundsAndMergeRemainManualAndRejectStaleOrInvalidChanges() = runBlocking {
        val session = repository.begin("Editable", 5); clock += 120_000
        repository.checkpoint(session, points(session.start, 12), 120, TrackStatus.FINISHED)
        var d = repository.detail(session.track.id).first(); val initial = d
        repository.editSegment(session.track.id, d.revision, d.segments.single().id, SegmentEdit.Split(d.points[5].time))
        d = repository.detail(session.track.id).first(); assertEquals(2, d.segments.size); assertTrue(d.segments.all { it.manual })
        try { repository.editSegment(session.track.id, initial.revision, initial.segments.single().id, SegmentEdit.Type(MotionType.DRIVING)); fail() } catch (_: StaleRecordException) { }
        val first = d.segments.first()
        repository.editSegment(session.track.id, d.revision, first.id, SegmentEdit.Bounds(first.start, d.points[6].time))
        d = repository.detail(session.track.id).first(); assertEquals(d.segments.first().end, d.segments.last().start)
        val before = d.revision
        try { repository.editSegment(session.track.id, before, first.id, SegmentEdit.Bounds(d.points[1].time, d.points[8].time)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.detail(session.track.id).first().revision)
        repository.editSegment(session.track.id, before, first.id, SegmentEdit.Type(MotionType.CYCLING))
        d = repository.detail(session.track.id).first(); repository.editSegment(session.track.id, d.revision, first.id, SegmentEdit.Merge(false))
        repository.ensureSegments(session.track.id); d = repository.detail(session.track.id).first()
        assertEquals(1, d.segments.size); assertTrue(d.segments.single().manual); assertEquals(MotionType.CYCLING, d.segments.single().motion)
        val track = d.track!!; repository.edit(TrackDraft(track.id, track.revision, "Renamed", "Note"))
        try { repository.edit(TrackDraft(track.id, track.revision, "Stale", "")); fail() } catch (_: StaleRecordException) { }
        host.close(); assertEquals(d.segments, repository.detail(track.id).first().segments)
    }
    @Test fun largeTrackRelatedRangeAndCascadingTombstonesSurviveReopen() = runBlocking {
        val session = repository.begin("Large", 100); clock += 20_000_000
        val points = points(session.start, 1201)
        repository.checkpoint(session, points, 12000, TrackStatus.FINISHED)
        val records = RoomRecordRepository(host)
        records.save(listOf(
            RecordChange("diary_entries", "inside", """{"title":"Inside","content":"Body","mood":"calm","occurred_at":${points.first().time},"tags_json":"[]"}""", clock, "test"),
            RecordChange("diary_entries", "end", """{"title":"End","content":"Body","mood":"calm","occurred_at":${points.last().time},"tags_json":"[]"}""", clock, "test"),
            RecordChange("diary_entries", "outside", """{"title":"Outside","content":"Body","mood":"calm","occurred_at":${points.last().time + 1},"tags_json":"[]"}""", clock, "test")
        ))
        host.close(); val d = repository.detail(session.track.id).first()
        assertEquals(1201, d.points.size); assertEquals(setOf("inside", "end"), d.related.map { it.id }.toSet())
        assertEquals(1201, repository.tracks.first().single().pointCount)
        repository.delete(session.track.id, d.revision); assertTrue(repository.tracks.first().isEmpty())
        host.access { db -> val rows = db.records().trackRows(session.track.id)
            assertEquals(1203, rows.size); assertTrue(rows.all { !it.json.contains("\"deleted_at\":null") })
        }
        assertNotNull(records.find("diary_entries", "inside"))
    }
}
