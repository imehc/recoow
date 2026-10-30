package imehc.recoow.core.notifications

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.*
import imehc.recoow.core.calendar.IcuAnniversaryCalendar
import imehc.recoow.core.data.anniversaries.RoomAnniversariesRepository
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.anniversaries.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class AnniversaryNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation(); private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var root: File; private lateinit var host: DatabaseHost; private lateinit var repository: RoomAnniversariesRepository; private lateinit var runtime: AnniversaryNotificationRuntime
    @Before fun setup() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        manager.cancelAll(); context.getSharedPreferences("anniversary-alarms", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.cacheDir, "anniversary-notice-${UUID.randomUUID()}").apply { mkdirs() }; val live = File(root, "live")
        host = DatabaseHost(context, live, LocalRollbackFiles(live, File(root, "rollback"), object : BackupPreferences {
            override suspend fun capture() = "{}"; override suspend fun restore(json: String) = Unit
        }, "test"))
        repository = RoomAnniversariesRepository(host, "test"); runtime = AnniversaryNotificationRuntime(context, repository, IcuAnniversaryCalendar(), host)
    }
    @After fun cleanup() = runBlocking { repository.records.first().forEach { repository.delete(it.id, it.revision) }; runtime.reconcile(); manager.cancelAll(); host.close(); root.deleteRecursively(); Unit }
    private fun awaitCount(count: Int) { repeat(100) { if (manager.activeNotifications.size == count) return; Thread.sleep(20) }; assertEquals(count, manager.activeNotifications.size) }
    @Test fun bothSchedulesRecoverAndDisablingCancelsWithoutDeletingRecord() = runBlocking {
        val at = ZonedDateTime.now().plusMinutes(10)
        repository.save(AnniversaryDraft(id = "future", title = "Future", occurredAt = at.toInstant().toEpochMilli(), yearly = false, minutes = at.hour * 60 + at.minute, lead = 5))
        assertTrue(runtime.reconcile()); val ledger = context.getSharedPreferences("anniversary-alarms", Context.MODE_PRIVATE)
        assertEquals(2, ledger.getStringSet("scheduled", emptySet())!!.size)
        runtime = AnniversaryNotificationRuntime(context, repository, IcuAnniversaryCalendar(), host); assertTrue(runtime.reconcile())
        assertEquals(2, ledger.getStringSet("scheduled", emptySet())!!.size)
        repository.save(AnniversaryDraft.from(repository.records.first().single()).copy(enabled = false)); runtime.reconcile()
        assertEquals(1, repository.records.first().size); assertTrue(ledger.getStringSet("scheduled", emptySet())!!.isEmpty())
    }
    @Test fun staleAlarmIsRejectedAndEachMainOrLeadDeliveryHasAReceipt() = runBlocking {
        val at = ZonedDateTime.now().withSecond(0).withNano(0).toInstant().toEpochMilli(); val local = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        repository.save(AnniversaryDraft(id = "today", title = "Original", occurredAt = at, yearly = false, minutes = local.hour * 60 + local.minute, lead = 5))
        val old = repository.records.first().single()
        repository.save(AnniversaryDraft.from(old).copy(title = "Current")); val current = repository.records.first().single()
        assertEquals(anniversaryAlarmRevision(current), anniversaryAlarmRevision(current.copy(revision = "acknowledged")))
        val key = Uri.parse("recoow-alarm://anniversaries/today/$at/main")
        runtime.deliver(key, at, anniversaryAlarmRevision(old)); awaitCount(0)
        runtime.deliver(key, at, anniversaryAlarmRevision(current)); awaitCount(1); val posted = manager.activeNotifications.single().postTime
        runtime.deliver(key, at, anniversaryAlarmRevision(current)); runtime.reconcile(); awaitCount(1); assertEquals(posted, manager.activeNotifications.single().postTime)
        runtime.deliver(Uri.parse("recoow-alarm://anniversaries/today/$at/lead"), at - 300_000, anniversaryAlarmRevision(current)); awaitCount(2)
        repository.delete(current.id, current.revision); runtime.reconcile(); awaitCount(0)
    }
}
