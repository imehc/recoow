package imehc.recoow.core.notifications

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.*
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.data.reminders.RoomRemindersRepository
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.reminders.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class ReminderNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var root: File
    private lateinit var host: DatabaseHost
    private lateinit var repository: RoomRemindersRepository
    private lateinit var runtime: ReminderNotificationRuntime
    @Before fun setup() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        manager.cancelAll()
        context.getSharedPreferences("reminder-alarms", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.cacheDir, "notification-test-${UUID.randomUUID()}").apply { mkdirs() }
        val directory = File(root, "live")
        host = DatabaseHost(context, directory, LocalRollbackFiles(directory, File(root, "rollback"), object : BackupPreferences {
            override suspend fun capture() = "{}"
            override suspend fun restore(json: String) = Unit
        }, "test"))
        repository = RoomRemindersRepository(host, "test-device")
        runtime = ReminderNotificationRuntime(context, repository, RoomMediaRepository(host, File(root, "cache"), "test-device"), host)
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun key(id: String, at: Long, kind: String = "main") = Uri.parse("recoow-alarm://reminders/$id/${Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()}/$kind")
    private fun awaitNotifications(count: Int) { repeat(100) { if (manager.activeNotifications.size == count) return; Thread.sleep(20) }; assertEquals(count, manager.activeNotifications.size) }
    @After fun cleanup() = runBlocking {
        repository.reminders.first().forEach { repository.delete(it.id, it.revision) }
        runtime.reconcile(); manager.cancelAll(); host.close(); root.deleteRecursively(); Unit
    }
    @Test fun sentNotificationSurvivesReconcileAndReplayButCheckInCancelsIt() = runBlocking {
        val at = System.currentTimeMillis() - 1_000
        repository.save(ReminderDraft(id = "notice", title = "Read", scheduledAt = at, kind = ScheduleKind.SINGLE))
        runtime.deliver(key("notice", at), at); awaitNotifications(1)
        val posted = manager.activeNotifications.single().postTime
        runtime.deliver(key("notice", at), at); runtime.reconcile(); awaitNotifications(1)
        assertEquals(posted, manager.activeNotifications.single().postTime)
        repository.act("notice", ReminderAction.CHECK_IN); runtime.reconcile(); awaitNotifications(0)
    }
    @Test fun reconcileRebuildsBothAlarmsAndCheckInCancelsThem() = runBlocking {
        val at = System.currentTimeMillis() + 600_000
        repository.save(ReminderDraft(id = "future", title = "Future", scheduledAt = at, kind = ScheduleKind.SINGLE, lead = 5))
        runtime.reconcile(); assertTrue(runtime.state.value.allowed)
        assertEquals(2, context.getSharedPreferences("reminder-alarms", Context.MODE_PRIVATE).getStringSet("scheduled", emptySet())!!.size)
        runtime = ReminderNotificationRuntime(context, repository, RoomMediaRepository(host, File(root, "reopened-cache"), "test-device"), host)
        runtime.reconcile()
        assertEquals(2, context.getSharedPreferences("reminder-alarms", Context.MODE_PRIVATE).getStringSet("scheduled", emptySet())!!.size)
        repository.act("future", ReminderAction.CHECK_IN); runtime.reconcile()
        assertTrue(context.getSharedPreferences("reminder-alarms", Context.MODE_PRIVATE).getStringSet("scheduled", emptySet())!!.isEmpty())
    }
    @Test fun changingReminderTimeAllowsANewDeliveryForTheSameDay() = runBlocking {
        val at = System.currentTimeMillis() - 2_000
        repository.save(ReminderDraft(id = "changed", title = "Before", scheduledAt = at, kind = ScheduleKind.SINGLE))
        runtime.deliver(key("changed", at), at); awaitNotifications(1)
        val current = repository.reminders.first().single()
        repository.save(ReminderDraft.from(current).copy(title = "After", scheduledAt = at + 1_000))
        runtime.deliver(key("changed", at), at) // Old PendingIntent must not publish the edited record.
        assertEquals("🔔 Before", manager.activeNotifications.single().notification.extras.getString("android.title"))
        runtime.deliver(key("changed", at + 1_000), at + 1_000)
        repeat(100) { if (manager.activeNotifications.single().notification.extras.getString("android.title") == "🔔 After") return@runBlocking; Thread.sleep(20) }
        fail("Edited reminder was not delivered")
    }
    @Test fun readyImageUsesBigPictureAndMissingObjectFallsBackToText() = runBlocking {
        val at = System.currentTimeMillis() - 1_000
        val image = File(root, "photo.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle()
        val draft = ReminderDraft(id = "picture", title = "Picture", scheduledAt = at, kind = ScheduleKind.SINGLE,
            imageEdit = ReminderImageEdit.FILE, imageFile = image.path, hasImage = true)
        repository.save(draft); repository.save(draft.copy(id = "missing"))
        runtime.deliver(key("picture", at), at); awaitNotifications(1)
        assertEquals("android.app.Notification\$BigPictureStyle", manager.activeNotifications.single().notification.extras.getString("android.template"))
        host.mediaDirectory.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        runtime.deliver(key("missing", at), at); awaitNotifications(2)
        val missing = manager.activeNotifications.single { it.tag == key("missing", at).toString() }
        assertEquals("android.app.Notification\$BigTextStyle", missing.notification.extras.getString("android.template"))
    }

}
