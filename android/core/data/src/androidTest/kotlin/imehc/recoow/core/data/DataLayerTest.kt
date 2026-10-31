package imehc.recoow.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import imehc.recoow.core.backup.BackupPreferences
import imehc.recoow.core.backup.LocalRollbackFiles
import imehc.recoow.core.data.records.ReceivedRecordStore
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.data.preferences.PreferenceBackupAdapter
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.database.SyncTarget
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DataLayerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var host: DatabaseHost
    private lateinit var recovery: LocalRollbackFiles
    private lateinit var preferences: TestPreferences
    private lateinit var records: RoomRecordRepository
    private val scope = "test-account-generation"

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "records-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        preferences = TestPreferences()
        recovery = LocalRollbackFiles(File(root, "live"), File(root, "backups"), preferences, "test-installation")
        host = newHost()
        records = RoomRecordRepository(host) { ZoneId.of("Asia/Shanghai") }
    }
    @After fun teardown() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private fun newHost() = DatabaseHost(context, File(root, "live"), recovery)
    private class TestPreferences : BackupPreferences {
        var value = "original"
        var failCapture = false
        override suspend fun capture(): String { check(!failCapture); return value }
        override suspend fun restore(json: String) { value = json }
    }
    private fun change(table: String, id: String = table, patch: JsonObject = fixture(table)) =
        RecordChange(table, id, patch.toString(), 1_700_000_000_000L, "test-device")
    private fun fixture(table: String): JsonObject = buildJsonObject {
        val contract = EntityContracts.registry.entity(table)
        for (field in contract.fields) {
            if (field.name in setOf("id", "created_at", "updated_at", "deleted_at", "device_id", "server_version") || field.derived || field.binary) continue
            val dependency = contract.dependencies.firstOrNull { it.field == field.name }
            val value = when {
                dependency != null -> JsonPrimitive(dependency.table)
                field.name == "mime_type" -> JsonPrimitive("image/jpeg")
                field.nullable -> JsonNull
                field.default != null -> field.default!!
                field.enumValues.isNotEmpty() -> JsonPrimitive(field.enumValues.first())
                field.name == "date_key" -> JsonPrimitive("2023-11-14")
                field.jsonText -> JsonPrimitive(if (field.name == "stored_value_effects") "{}" else "[]")
                field.type == "int" -> JsonPrimitive(if (field.name.endsWith("_at") || field.name == "timestamp_ms") 1_700_000_000_000L else 1L)
                field.type == "real" -> JsonPrimitive(1.25)
                field.type == "bool" -> JsonPrimitive(true)
                else -> JsonPrimitive("test")
            }
            put(field.name, value)
        }
    }
    private fun JsonObject.patch(vararg fields: Pair<String, JsonElement>) = JsonObject(toMutableMap().apply { putAll(fields) })
    private suspend fun count(table: String) = host.access { db ->
        db.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
    }
    private suspend fun attach(): ReceivedRecordStore = ReceivedRecordStore(host).also {
        it.attach(SyncTarget(scope, "connection", "account", "generation"))
    }

    @Test fun allEntitiesPersistWithExactIntegersBlobsConstraintsAndQueryableIndexes() = runBlocking {
        val tables = EntityContracts.registry.entities.keys
        assertEquals(21, tables.size)
        val rows = tables.map { table ->
            var row = change(table)
            if (table == "bills") row = change(table, patch = fixture(table).patch(
                "original_amount_cents" to JsonPrimitive(9_007_199_254_740_993L)))
            if (table == "media_attachments") row = row.copy(binaryFields = mapOf("data" to ByteArray(3 * 1024 * 1024) { 7 }))
            row
        }
        records.save(rows)
        for (table in tables) assertEquals(table, 1L, count(table))
        host.access { db ->
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT original_amount_cents, typeof(original_amount_cents) FROM bills").use {
                assertTrue(it.moveToFirst()); assertEquals(9_007_199_254_740_993L, it.getLong(0)); assertEquals("integer", it.getString(1))
            }
            sql.query("SELECT length(data) FROM media_attachments").use { it.moveToFirst(); assertEquals(3 * 1024 * 1024, it.getInt(0)) }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            for (query in listOf("SELECT id FROM bills WHERE deleted_at IS NULL ORDER BY occurred_at",
                "SELECT id FROM track_points WHERE track_id = 'tracks' AND deleted_at IS NULL ORDER BY timestamp_ms")) {
                sql.query("EXPLAIN QUERY PLAN $query").use { it.moveToFirst(); assertTrue(it.getString(3), it.getString(3).contains("INDEX")) }
            }
        }
        val remote = attach()
        val row = envelope("media_attachments", "media_attachments", 1, fixture("media_attachments").patch("title" to JsonPrimitive("remote title")))
        remote.receivePage(scope, host.sessionToken(), "binary", row.toByteArray(), listOf(row), 1)
        assertEquals(1, remote.applyPending(scope, host.sessionToken()))
        host.access { db -> db.openHelper.writableDatabase.query("SELECT length(data), title FROM media_attachments").use {
            it.moveToFirst(); assertEquals(3 * 1024 * 1024, it.getInt(0)); assertEquals("remote title", it.getString(1))
        } }
        assertEquals(21L, count("local_changes"))
        host.close()
        host = newHost()
        assertEquals(21L, count("entity_snapshots"))
    }

    @Test fun localTransactionsEmitFlowsFreezeOutboxAndRollBackOnOverflow() = runBlocking {
        attach()
        records.observe("decision_collections").test {
            assertTrue(awaitItem().isEmpty())
            records.save(listOf(change("decision_collections", "choice")))
            assertEquals("choice", awaitItem().single().id)
            records.save(listOf(change("decision_collections", "choice", buildJsonObject { put("title", "edited") })))
            assertTrue(awaitItem().single().snapshotJson.contains("edited"))
            cancelAndIgnoreRemainingEvents()
        }
        host.access { db -> db.openHelper.writableDatabase.query("SELECT payloadJson FROM sync_outbox ORDER BY localChangeId").use {
            assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("edited"))
            assertTrue(it.moveToNext()); assertTrue(it.getString(0).contains("edited"))
        } }
        records.save(listOf(change("stored_value_accounts")))
        records.save(listOf(change("stored_value_adjustments", "max", fixture("stored_value_adjustments").patch("amount_cents" to JsonPrimitive(Long.MAX_VALUE)))))
        val before = count("local_changes")
        try {
            records.save(listOf(change("decision_collections", "must-rollback"), change("stored_value_adjustments", "overflow")))
            fail("Overflow must abort the whole transaction")
        } catch (_: ArithmeticException) { }
        assertNull(records.find("decision_collections", "must-rollback"))
        assertEquals(before, count("local_changes"))
        assertEquals(before, count("sync_outbox"))
    }

    private fun envelope(table: String, id: String, version: Long, fields: JsonObject, dataVersion: Int = 1): String = buildJsonObject {
        put("table", table); put("id", id); put("version", version); put("dataVersion", dataVersion)
        put("updatedAtMs", 1_700_000_000_000L); put("deviceId", "remote-device"); put("deletedAtMs", JsonNull)
        put("snapshot", fields.patch("created_at" to JsonPrimitive(1_600_000_000_000L)))
    }.toString()

    @Test fun remoteDependenciesUnknownRowsAndUnsupportedVersionsNeverSkipAppliedCursor() = runBlocking {
        val remote = attach()
        val token = host.sessionToken()
        val rows = listOf(
            envelope("future_entity", "future", 1, buildJsonObject { put("title", "future") }),
            envelope("decision_options", "option", 2, fixture("decision_options").patch("image_asset_id" to JsonNull)),
            envelope("decision_collections", "decision_collections", 3, fixture("decision_collections").patch("future_field" to buildJsonObject { put("value", 42) })),
            envelope("decision_collections", "new-version", 4, fixture("decision_collections"), dataVersion = 99),
        )
        remote.receivePage(scope, token, "one", rows.toString().toByteArray(), rows, 4)
        assertEquals(2, remote.applyPending(scope, token))
        assertEquals(1L, count("decision_options"))
        assertEquals(0L, count("local_changes"))
        host.access { db ->
            assertEquals(4L, db.records().target(scope)!!.receivedCursor)
            assertEquals(0L, db.records().target(scope)!!.appliedCursor)
            assertEquals(2, db.records().inbox(scope).size)
        }
        remote.receivePage(scope, token, "one", rows.toString().toByteArray(), rows, 4)
        assertEquals(0, remote.applyPending(scope, token))
        val tombstone = Json.parseToJsonElement(envelope("decision_options", "option", 5, buildJsonObject { })).jsonObject
            .patch("deletedAtMs" to JsonPrimitive(1_700_000_000_001L)).toString()
        remote.receivePage(scope, token, "tombstone", tombstone.toByteArray(), listOf(tombstone), 5)
        assertEquals(1, remote.applyPending(scope, token))
        assertEquals(JsonPrimitive(1_700_000_000_001L), Json.parseToJsonElement(records.find("decision_options", "option")!!.snapshotJson).jsonObject["deleted_at"])
        records.save(listOf(change("decision_collections", "decision_collections", buildJsonObject { put("title", "local"); put("note", JsonNull) })))
        val snapshot = Json.parseToJsonElement(records.find("decision_collections", "decision_collections")!!.snapshotJson).jsonObject
        assertEquals(42, snapshot.getValue("future_field").jsonObject.getValue("value").jsonPrimitive.int)
        assertEquals(JsonNull, snapshot["note"])
        assertEquals(1L, count("sync_outbox"))
        try { remote.receivePage(scope, token, "malformed", "raw response".toByteArray(), listOf("not-json"), 6); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(3L, count("sync_received_pages"))
        host.access { assertEquals(5L, it.records().target(scope)!!.receivedCursor) }
    }

    @Test fun completeRemoteDatasetMaterializesAllEntitiesWithoutCreatingLocalMutations() = runBlocking {
        val remote = attach()
        val token = host.sessionToken()
        val entities = EntityContracts.registry.entities.values.toList().reversed()
        val envelopes = entities.mapIndexed { index, entity ->
            var fields = fixture(entity.table)
            if (entity.table == "media_assets") {
                val reference = imehc.recoow.core.database.MediaObjects(host.mediaDirectory).capture(byteArrayOf(1, 2, 3), "image/jpeg")
                fields = fields.patch("checksum" to JsonPrimitive(reference.checksum), "byte_count" to JsonPrimitive(reference.size),
                    "_media" to buildJsonObject { put("object", reference.json()) })
            }
            envelope(entity.table, entity.table, index + 1L, fields, entity.version)
        }
        remote.receivePage(scope, token, "full-dataset", envelopes.toString().toByteArray(), envelopes, 21)
        assertEquals(21, remote.applyPending(scope, token))
        for (entity in entities) assertEquals(entity.table, 1L, count(entity.table))
        assertEquals(0L, count("local_changes"))
        assertEquals(0L, count("sync_outbox"))
        host.access { assertEquals(21L, it.records().target(scope)!!.appliedCursor) }
        assertEquals(0, remote.applyPending(scope, token))
    }

    @Test fun guardedFailureRestoresDatabaseMediaPreferencesAndInvalidatesOldSession() = runBlocking {
        records.save(listOf(change("decision_collections")))
        host.mediaDirectory.mkdirs()
        File(host.mediaDirectory, "photo").writeBytes(byteArrayOf(1, 2, 3))
        val token = host.sessionToken()
        try {
            host.guarded("failure-test") { db, media ->
                db.openHelper.writableDatabase.execSQL("DELETE FROM decision_collections")
                File(media, "photo").writeBytes(byteArrayOf(9))
                preferences.value = "changed"
                error("injected")
            }
            fail()
        } catch (error: IllegalStateException) { assertEquals("injected", error.message) }
        assertEquals(1L, count("decision_collections"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(host.mediaDirectory, "photo").readBytes())
        assertEquals("original", preferences.value)
        try { host.access(token) { }; fail("Stale session must fail") } catch (_: IllegalStateException) { }
        preferences.failCapture = true
        var actionRan = false
        try { host.guarded("backup-fails") { _, _ -> actionRan = true }; fail() } catch (_: IllegalStateException) { }
        assertFalse(actionRan)
        preferences.failCapture = false
        assertEquals(1L, count("decision_collections"))
        try {
            host.guarded("invalid-result") { db, _ -> db.openHelper.writableDatabase.execSQL("PRAGMA user_version = 2") }
            fail("Invalid maintenance result must be rolled back before commit")
        } catch (_: IllegalStateException) { }
        assertEquals(1L, count("decision_collections"))
        host.access { assertEquals(1, it.openHelper.writableDatabase.version) }
    }

    @Test fun interruptedMaintenanceRecoversBeforeReopeningAndFutureVersionIsPreserved() = runBlocking {
        records.save(listOf(change("decision_collections")))
        host.close()
        recovery.begin("interrupted")
        SQLiteDatabase.openDatabase(host.databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM decision_collections")
        }
        preferences.value = "interrupted"
        host = newHost()
        assertEquals(1L, count("decision_collections"))
        assertEquals("original", preferences.value)
        host.close()
        SQLiteDatabase.openDatabase(host.databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        val before = host.databaseFile.readBytes()
        host = newHost()
        try { host.access { }; fail("Downgrade must fail") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, host.databaseFile.readBytes())
    }

    @Test fun reminderProjectionCountsLocalDatesAcrossDSTAndRecomputesAfterTombstones() = runBlocking {
        records = RoomRecordRepository(host) { ZoneId.of("America/New_York") }
        val start = Instant.parse("2025-03-08T14:00:00Z").toEpochMilli()
        val end = Instant.parse("2025-03-10T13:00:00Z").toEpochMilli()
        records.save(listOf(change("reminders", patch = fixture("reminders").patch(
            "schedule_kind" to JsonPrimitive("dateRange"), "scheduled_at" to JsonPrimitive(start),
            "end_at" to JsonPrimitive(end), "image_asset_id" to JsonNull))))
        suspend fun completedAt() = Json.parseToJsonElement(records.find("reminders", "reminders")!!.snapshotJson).jsonObject["completed_at"]
        repeat(2) { day ->
            records.save(listOf(change("reminder_completions", "day$day", fixture("reminder_completions").patch(
                "date_key" to JsonPrimitive("2025-03-0${day + 8}"), "completed_at" to JsonPrimitive(start + day * 86_400_000L)))))
        }
        assertEquals(JsonNull, completedAt()) // 47 hours span three local dates, not two.
        records.save(listOf(change("reminder_completions", "day2", fixture("reminder_completions").patch(
            "date_key" to JsonPrimitive("2025-03-10"), "completed_at" to JsonPrimitive(end)))))
        assertEquals(JsonPrimitive(end), completedAt())
        records.save(listOf(change("reminder_completions", "day2", buildJsonObject { }).copy(deleted = true)))
        assertEquals(JsonNull, completedAt())
        assertEquals(3L, count("reminder_completions"))
    }

    @Test fun corruptBackupBlocksRecoveryWithoutTouchingLiveData() = runBlocking {
        records.save(listOf(change("decision_collections")))
        host.close()
        val bundle = recovery.begin("corruption-test")
        val before = host.databaseFile.readBytes()
        File(bundle, "records.sqlite").appendBytes(byteArrayOf(99))
        host = newHost()
        try { host.access { }; fail("Corrupt backup must block reopening") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, host.databaseFile.readBytes())
        assertTrue(File(root, "backups/pending-restore").exists())
    }

    @Test fun realPreferenceAdapterRestoresPersistentValuesWithoutLosingTypes() = runBlocking {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { File(root, "backup-test.preferences_pb") })
        try {
            store.edit {
                it[stringPreferencesKey("appearance")] = "DARK"
                it[longPreferencesKey("exact-integer")] = 9_007_199_254_740_993L
                it[stringSetPreferencesKey("hidden-tools")] = setOf("bills", "future")
            }
            val before = store.data.first()
            val adapter = PreferenceBackupAdapter(context, store)
            val snapshot = adapter.capture()
            store.edit { it.clear(); it[stringPreferencesKey("appearance")] = "LIGHT" }
            adapter.restore(snapshot)
            assertEquals(before, store.data.first())
        } finally { job.cancelAndJoin() }
    }
}
