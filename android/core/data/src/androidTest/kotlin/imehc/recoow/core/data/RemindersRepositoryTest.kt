package imehc.recoow.core.data

import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.*
import imehc.recoow.core.data.reminders.RoomRemindersRepository
import imehc.recoow.core.data.records.*
import imehc.recoow.core.database.*
import imehc.recoow.core.domain.reminders.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemindersRepositoryTest {
    private lateinit var root: File
    private lateinit var host: DatabaseHost
    private lateinit var repository: RoomRemindersRepository
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = LocalDate.of(2026, 10, 26)
    private var clock = localTime(start, 600, zone)
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "reminder-test-${UUID.randomUUID()}").apply { mkdirs() }
        val directory = File(root, "live")
        host = DatabaseHost(context, directory, LocalRollbackFiles(directory, File(root, "rollback"), object : BackupPreferences {
            override suspend fun capture() = "{}"
            override suspend fun restore(json: String) = Unit
        }, "test"))
        repository = RoomRemindersRepository(host, "test-device", { clock }, { zone })
    }
    @After fun cleanup() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private fun draft(kind: ScheduleKind = ScheduleKind.GOAL) = ReminderDraft(id = "task", title = "Read", scheduledAt = clock, timeMinutes = 600, kind = kind, targetDays = "3")
    private suspend fun task() = repository.reminders.first().single()
    private suspend fun count(table: String) = host.access { db -> db.openHelper.writableDatabase.query("SELECT count(*) FROM local_changes WHERE tableName = ?", arrayOf(table)).use { it.moveToFirst(); it.getLong(0) } }
    @Test fun checkInWritesOnlyIndependentDetailAndStaleEditorCannotOverwriteIt() = runBlocking {
        repository.save(draft()); val old = task(); val parentCount = count("reminders")
        repository.act("task", ReminderAction.CHECK_IN)
        assertEquals(parentCount, count("reminders")); assertEquals(1L, count("reminder_completions")); assertEquals(setOf(start), task().dates)
        try { repository.save(ReminderDraft.from(old).copy(title = "stale")); fail("Expected stale form") } catch (_: StaleReminderException) { }
        repository.act("task", ReminderAction.UNDO_TODAY)
        assertTrue(task().dates.isEmpty())
        val fields = host.access { Json.parseToJsonElement(it.records().snapshot("reminder_completions", "task:$start")!!.json).jsonObject }
        assertNotEquals(JsonNull, fields["deleted_at"])
    }
    @Test fun makeUpCanFillNonconsecutiveDatesAndPauseResumeKeepHistory() = runBlocking {
        repository.save(draft()); clock = localTime(start.plusDays(4), 600, zone)
        repository.makeUp("task", setOf(start, start.plusDays(2)), "travel")
        assertEquals(2L, task().totalCheckIns); assertTrue(task().completions.all { it.kind == CheckInKind.MAKE_UP && it.note == "travel" })
        repository.act("task", ReminderAction.PAUSE, "rest")
        clock += 86_400_000
        repository.act("task", ReminderAction.RESUME)
        assertEquals(86_400_000L, task().pausedMillis); assertEquals(start, task().start(zone)); assertEquals(2, task().dates.size)
        repository.act("task", ReminderAction.END_EARLY, "finished")
        assertTrue(task().completed(zone)); assertEquals("finished", task().endReason)
    }
    @Test fun completionRestoreAndTaskDeletionPreserveTheCorrectTombstones() = runBlocking {
        repository.save(draft(ScheduleKind.WEEKDAYS).copy(targetDays = "2", importedDays = "1"))
        repository.act("task", ReminderAction.CHECK_IN)
        assertTrue(task().completed(zone))
        repository.act("task", ReminderAction.RESTORE)
        assertFalse(task().completed(zone)); assertEquals(1L, task().importedDays); assertTrue(task().dates.isEmpty())
        repository.act("task", ReminderAction.CHECK_IN)
        val revision = task().revision
        repository.delete("task", revision)
        assertTrue(repository.reminders.first().isEmpty())
        val detail = host.access { Json.parseToJsonElement(it.records().snapshot("reminder_completions", "task:$start")!!.json).jsonObject }
        assertEquals(JsonNull, detail["deleted_at"])
    }
    @Test fun continuousChallengeRejectsNewDayUntilMissedDayIsMadeUp() = runBlocking {
        repository.save(draft(ScheduleKind.CONTINUOUS)); clock += 86_400_000
        try { repository.act("task", ReminderAction.CHECK_IN); fail("Expected missing-day rejection") } catch (_: IllegalArgumentException) { }
        repository.makeUp("task", setOf(start), "missed")
        repository.act("task", ReminderAction.CHECK_IN)
        assertEquals(setOf(start, start.plusDays(1)), task().dates)
    }
    @Test fun firstEditMaterializesLegacyHistoryAndLaterTombstonesNeverResurrect() = runBlocking {
        repository.save(draft())
        host.access { db ->
            val old = db.records().snapshot("reminders", "task")!!
            val fields = Json.parseToJsonElement(old.json).jsonObject.toMutableMap()
            fields["completed_date_keys"] = JsonPrimitive(start.toString())
            fields["completion_records"] = JsonPrimitive("[]")
            db.records().put(old.copy(json = JsonObject(fields).toString()))
        }
        clock += 86_400_000
        repository.act("task", ReminderAction.CHECK_IN)
        assertEquals(setOf(start, start.plusDays(1)), task().dates)
        repository.deleteCompletion("task", start)
        repository.save(ReminderDraft.from(task()).copy(title = "edited"))
        assertEquals(setOf(start.plusDays(1)), task().dates)
    }

}
