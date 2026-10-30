package imehc.recoow.core.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.LocalRollbackFiles
import imehc.recoow.core.data.backup.RoomDataManagement
import imehc.recoow.core.data.preferences.PreferenceBackupAdapter
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.domain.backup.*
import imehc.recoow.core.domain.records.RecordChange
import imehc.recoow.core.domain.sync.DeviceIdentity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackupRepositoryTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var host: DatabaseHost
    private lateinit var data: RoomDataManagement
    private lateinit var records: RoomRecordRepository
    private lateinit var store: androidx.datastore.core.DataStore<Preferences>
    private val job = SupervisorJob()
    private val tables get() = EntityContracts.registry.entities.keys
    private val assetBytes = ByteArray(3 * 1024 * 1024) { (it % 239).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(assetBytes).joinToString("") { "%02x".format(it) }
    @Before fun setup() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(original.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(original) {
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { File(root, "settings.preferences_pb") }
        val live = File(context.noBackupFilesDir, "records")
        host = DatabaseHost(context, live, LocalRollbackFiles(live, File(context.noBackupFilesDir, "rollback"), PreferenceBackupAdapter(context, store), "test-install"))
        data = RoomDataManagement(context, host, store, object : DeviceIdentity { override val id = "local-device" })
        records = RoomRecordRepository(host)
    }
    @After fun cleanup() = runBlocking { host.close(); job.cancelAndJoin(); root.deleteRecursively(); Unit }
    private fun fixture(table: String) = buildJsonObject {
        val contract = EntityContracts.registry.entity(table)
        for (field in contract.fields) {
            if (field.name in setOf("id", "created_at", "updated_at", "deleted_at", "device_id", "server_version") || field.derived || field.binary) continue
            val dependency = contract.dependencies.firstOrNull { it.field == field.name }
            put(field.name, when {
                dependency != null -> JsonPrimitive(dependency.table)
                field.nullable -> JsonNull
                field.default != null -> field.default!!
                field.enumValues.isNotEmpty() -> JsonPrimitive(field.enumValues.first())
                field.name == "date_key" -> JsonPrimitive("2026-10-30")
                field.jsonText -> JsonPrimitive(if (field.name == "stored_value_effects") "{}" else "[]")
                field.type == "int" -> JsonPrimitive(if (field.name.endsWith("_at") || field.name == "timestamp_ms") 1_798_000_000_000L else 1L)
                field.type == "real" -> JsonPrimitive(1.25)
                field.type == "bool" -> JsonPrimitive(true)
                else -> JsonPrimitive("test")
            })
        }
        when (table) {
            "media_assets" -> { put("checksum", hash); put("byte_count", assetBytes.size); put("storage_key", "sync/$hash"); put("mime_type", "image/jpeg"); put("kind", "image") }
            "media_attachments" -> { put("owner_type", "diary"); put("owner_id", "diary_entries"); put("mime_type", "audio/mp4"); put("kind", "audio") }
            "bills" -> put("original_amount_cents", 9_007_199_254_740_993L)
            "food_entries" -> { put("bill_id", "bills"); put("bill_ids_json", "[\"bills\"]") }
            "tracks" -> put("status", "finished")
        }
    }
    private suspend fun seed() {
        records.save(tables.map { table -> RecordChange(table, table, fixture(table).toString(), 1_798_000_000_000L, "source-device",
            binaryFields = when(table) { "media_assets" -> mapOf("inline_data" to assetBytes); "media_attachments" -> mapOf("data" to assetBytes.reversedArray()); else -> emptyMap() }) })
        host.access { it.openHelper.writableDatabase.execSQL("UPDATE media_assets SET inline_data=NULL") }
        store.edit { it[stringPreferencesKey("appearance")] = "DARK"; it[booleanPreferencesKey("addsPickedPhotosToMediaLibrary")] = true; it[booleanPreferencesKey("savesCameraPhotosToLibrary")] = false; it[stringSetPreferencesKey("hiddenHomeTools")] = setOf("foodJournal") }
    }
    private suspend fun backup(): File = File(root, "output-${UUID.randomUUID()}.recoowbackup").also { file -> data.export { file.outputStream() } }
    private suspend fun count(table: String) = host.access { db -> db.openHelper.writableDatabase.query("SELECT count(*) FROM \"$table\"").use { it.moveToFirst(); it.getInt(0) } }
    private suspend fun preview(file: File) = data.preview { file.inputStream() }

    @Test fun allEntitiesMediaPreferencesAndIntegersRoundTripWithoutChangingOnlineBlobs() = runBlocking {
        seed()
        val exported = backup()
        host.access { db -> db.openHelper.writableDatabase.query("SELECT inline_data FROM media_assets").use { it.moveToFirst(); assertTrue(it.isNull(0)) } }
        val before = tables.associateWith { records.find(it, it)!!.snapshotJson }
        val token = host.sessionToken()
        val prepared = preview(exported)
        assertEquals(21, prepared.preview.tableCounts.size)
        assertEquals(36, prepared.preview.databaseSchemaVersion)
        assertEquals("android", prepared.preview.sourcePlatform)
        records.save(listOf(RecordChange("diary_entries", "diary_entries", "{\"content\":\"changed locally\"}", 1_798_000_000_001L, "local-device")))
        assertEquals(21, data.import(prepared, ImportMode.REPLACE_ALL, emptySet()))
        assertThrows(IllegalStateException::class.java) { runBlocking { host.access(token) { } } }
        for (table in tables) {
            assertEquals(table, 1, count(table))
            val original = Json.parseToJsonElement(before.getValue(table)).jsonObject.filterKeys { it != "_media" }
            val restored = Json.parseToJsonElement(records.find(table, table)!!.snapshotJson).jsonObject.filterKeys { it != "_media" }
            assertEquals(table, original, restored)
        }
        assertArrayEquals(assetBytes, File(host.mediaDirectory, "sync/$hash").readBytes())
        host.access { db -> assertArrayEquals(assetBytes.reversedArray(), PhysicalRecords.binary(db.openHelper.writableDatabase, "media_attachments", "media_attachments", "data")) }
        assertEquals("DARK", store.data.first()[stringPreferencesKey("appearance")])
        assertEquals(true, store.data.first()[booleanPreferencesKey("addsPickedPhotosToMediaLibrary")])
        assertEquals(false, store.data.first()[booleanPreferencesKey("savesCameraPhotosToLibrary")])
        assertEquals(setOf("foodJournal"), store.data.first()[stringSetPreferencesKey("hiddenHomeTools")])
        assertEquals(2, count("notification_jobs"))
        data.discard(prepared)
    }

    @Test fun replacementPreservesHistoricalAttachmentsOfDeletedOwners() = runBlocking {
        seed()
        records.save(listOf("diary_entries", "media_attachments").map { table ->
            RecordChange(table, table, "{}", 1_798_000_000_001L, "source-device", deleted = true)
        })
        val prepared = preview(backup())
        assertEquals(21, data.import(prepared, ImportMode.REPLACE_ALL, emptySet()))
        assertEquals(1, count("media_attachments"))
        val attachment = Json.parseToJsonElement(records.find("media_attachments", "media_attachments")!!.snapshotJson).jsonObject
        assertEquals(JsonPrimitive(1_798_000_000_001L), attachment["deleted_at"])
        host.access { db -> assertArrayEquals(assetBytes.reversedArray(), PhysicalRecords.binary(db.openHelper.writableDatabase, "media_attachments", "media_attachments", "data")) }
        data.discard(prepared)
    }

    @Test fun scopedMergeIsIdempotentAndPreservesExistingIDsAndPreferences() = runBlocking {
        seed(); val exported = backup()
        data.reset()
        val local = JsonObject(fixture("food_entries").toMutableMap().apply { put("bill_id", JsonNull); put("bill_ids_json", JsonPrimitive("[]")); put("note", JsonPrimitive("keep-local")) })
        records.save(listOf(RecordChange("food_entries", "food_entries", local.toString(), 1_798_000_000_001L, "local-device")))
        val before = records.find("food_entries", "food_entries")
        val prepared = preview(exported)
        assertEquals(1, data.import(prepared, ImportMode.MERGE_MISSING, setOf(ImportScope.FOOD)))
        assertEquals(before, records.find("food_entries", "food_entries"))
        assertEquals(0, data.import(prepared, ImportMode.MERGE_MISSING, setOf(ImportScope.FOOD)))
        assertEquals(0, count("bills")); assertEquals(0, count("diary_entries"))
        assertNull(store.data.first()[stringPreferencesKey("appearance")])
        data.discard(prepared)
    }

    @Test fun selectedFoodDropsMissingBillLinksAndQueuesBoundUploads() = runBlocking {
        seed(); val exported = backup(); data.reset()
        host.access { db -> db.withTransaction { db.records().put(SyncTarget("scope", "connection", "account", "generation")); db.records().put(DatasetState(activeScope = "scope")) } }
        val prepared = preview(exported)
        data.import(prepared, ImportMode.MERGE_MISSING, setOf(ImportScope.FOOD))
        val food = Json.parseToJsonElement(records.find("food_entries", "food_entries")!!.snapshotJson).jsonObject
        assertEquals(JsonNull, food["bill_id"]); assertEquals(JsonPrimitive("[]"), food["bill_ids_json"])
        assertEquals(2, count("sync_outbox"))
        data.import(prepared, ImportMode.REPLACE_ALL, emptySet())
        assertEquals(21, count("sync_outbox")); assertEquals(1, count("sync_targets"))
        assertEquals("scope", host.access { it.records().activeScope() })
        data.discard(prepared)
    }

    @Test fun invalidProjectionRollsBackDatabaseMediaPreferencesAndRejectsChangedPreview() = runBlocking {
        seed(); val exported = backup()
        SQLiteDatabase.openDatabase(exported.path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.execSQL("UPDATE bills SET stored_value_effects='[]'") }
        val prepared = preview(exported)
        val before = records.find("bills", "bills")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.import(prepared, ImportMode.REPLACE_ALL, emptySet()) } }
        assertEquals(before, records.find("bills", "bills")); assertEquals(21, count("entity_snapshots"))
        assertTrue(File(host.mediaDirectory, "sync/$hash").isFile)
        assertEquals("DARK", store.data.first()[stringPreferencesKey("appearance")])
        prepared.file.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.import(prepared, ImportMode.REPLACE_ALL, emptySet()) } }
        data.discard(prepared)
    }

    @Test fun missingOrCorruptMediaCannotProduceACompleteBackup() = runBlocking {
        seed(); File(host.mediaDirectory, "sync/$hash").writeText("damaged")
        var opened = false
        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.export { opened = true; java.io.ByteArrayOutputStream() } } }
        assertFalse(opened)
    }

    @Test fun damagedOnlineDatabaseIsPreservedInsteadOfDeletedBySqlite() = runBlocking {
        seed(); host.close()
        java.io.RandomAccessFile(host.databaseFile, "rw").use { it.seek(0); it.write(ByteArray(16) { 42 }) }
        val damaged = host.databaseFile.readBytes()
        assertThrows(Exception::class.java) { runBlocking { host.access { } } }
        assertTrue(host.databaseFile.isFile)
        assertArrayEquals(damaged, host.databaseFile.readBytes())
    }

    @Test fun optimizeRetainsHistoricalMediaAndResetJournalsCleanup() = runBlocking {
        seed()
        val orphan = File(host.mediaDirectory, "orphan").apply { writeBytes(ByteArray(500_000)) }
        val before = data.usage()
        assertEquals(before.database + before.media + before.backups + before.cache, before.total)
        data.optimize()
        assertFalse(orphan.exists()); assertTrue(File(host.mediaDirectory, "sync/$hash").isFile)
        assertEquals(21, count("local_changes"))
        data.reset(); assertTrue(data.resetPending()); assertEquals(0, count("entity_snapshots")); assertEquals(0, count("sync_targets"))
        data.finishReset(); assertFalse(data.resetPending())
        assertEquals(0, data.usage().media); assertEquals(0, data.usage().backups)
    }
}
