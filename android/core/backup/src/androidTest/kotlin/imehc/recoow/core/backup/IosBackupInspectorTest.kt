package imehc.recoow.core.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.domain.backup.InvalidBackupException
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IosBackupInspectorTest {
    @get:Rule val temporary = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test fun readsIOSMetadataWithoutCopyingIdentityAndWithoutMaterializingLargeBlobs() = runTest {
        // Synthetic values in the format emitted by iOS DatabaseBackupMetadata (schema 36).
        val metadata = """{
            "formatVersion":1,"databaseSchemaVersion":36,"appVersion":"1.0","appBuild":"1",
            "exportedAt":"2026-10-29T00:00:00Z","sourceDeviceID":"fixture-device",
            "preferences":{"appearanceRawValue":"dark","languageRawValue":"chinese",
                "hiddenToolRouteIDs":["locationTracker","future-tool"],
                "addsPickedPhotosToMediaLibrary":false,"savesCameraPhotosToLibrary":true}
        }""".trimIndent()
        val source = File(temporary.root, "source.recoowbackup")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.execSQL("CREATE TABLE _recoow_backup_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO _recoow_backup_metadata VALUES (?, ?)", arrayOf("metadata", metadata))
            db.execSQL("CREATE TABLE media_assets (id TEXT PRIMARY KEY, inline_data BLOB)")
            db.execSQL("INSERT INTO media_assets VALUES (?, ?)", arrayOf("photo", ByteArray(3 * 1024 * 1024) { 42 }))
            db.execSQL("CREATE TABLE bills (id TEXT PRIMARY KEY, deleted_at INTEGER)")
            db.execSQL("INSERT INTO bills VALUES ('live', NULL), ('tombstone', 1)")
        }
        val before = source.readBytes()
        val scratch = File(temporary.root, "scratch")
        val preview = IosBackupInspector(scratch).inspect({ source.inputStream() })
        assertEquals(36, preview.databaseSchemaVersion)
        assertEquals("fixture-device", preview.sourceDeviceId)
        assertEquals(metadata, preview.metadataJson)
        assertEquals(2L, preview.tableCounts["bills"])
        assertEquals(3L * 1024 * 1024, preview.embeddedMediaBytes)
        assertArrayEquals(before, source.readBytes())
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun refusesInvalidOrOversizedFilesAndCleansTemporaryCopy() = runTest {
        val scratch = File(temporary.root, "scratch")
        val inspector = IosBackupInspector(scratch)
        for ((bytes, limit) in listOf("not SQLite".toByteArray() to 100L, ByteArray(32) to 16L)) {
            try {
                inspector.inspect({ ByteArrayInputStream(bytes) }, maximumBytes = limit)
                fail("Invalid input must fail")
            } catch (_: InvalidBackupException) { }
            assertTrue(scratch.listFiles().orEmpty().isEmpty())
        }
    }
}
