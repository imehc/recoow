package imehc.recoow.core.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.BackupPreferences
import imehc.recoow.core.backup.LocalRollbackFiles
import imehc.recoow.core.data.records.ReceivedRecordStore
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.domain.records.RecordChange
import imehc.recoow.core.domain.sync.*
import imehc.recoow.core.network.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real backend tests: pass syncBaseUrl via instrumentation arguments. No checked-in fixtures. */
class SyncIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = requireNotNull(InstrumentationRegistry.getArguments().getString("syncBaseUrl"))
    private val roots = mutableListOf<File>()
    private val hosts = mutableListOf<DatabaseHost>()
    private val factory = SyncApiFactory(true)
    private val timestamp = 1_700_000_000_000L
    private class MemoryVault : CredentialStore {
        var value: Credentials? = null
        override fun load() = value
        override fun save(credentials: Credentials?) { value = credentials }
        override val localDeviceId = UUID.randomUUID().toString()
    }
    private class TestSyncPreferences : SyncPreferences {
        private val values = mutableMapOf<String, Boolean>()
        private val success = mutableMapOf<String, Long>()
        private fun key(c: Credentials) = c.endpoint + "|" + c.account
        override fun enabled(connection: Credentials) = values[key(connection)] ?: false
        override fun setEnabled(connection: Credentials, value: Boolean) { values[key(connection)] = value }
        override fun lastSuccess(connection: Credentials) = success[key(connection)]
        override fun setLastSuccess(connection: Credentials, value: Long) { success[key(connection)] = value }
    }
    private data class Client(val host: DatabaseHost, val records: RoomRecordRepository, val vault: MemoryVault,
        val engine: SyncEngine, val store: SyncStore, val received: ReceivedRecordStore)
    private fun client(provider: SyncApiProvider = factory): Client {
        val root = File(context.cacheDir, "sync-test-${UUID.randomUUID()}").apply { mkdirs() }; roots += root
        val directory = File(root, "live")
        val backup = LocalRollbackFiles(directory, File(root, "backups"), object : BackupPreferences {
            override suspend fun capture() = "{}"
            override suspend fun restore(json: String) { }
        }, "test-installation")
        val host = DatabaseHost(context, directory, backup); hosts += host
        val received = ReceivedRecordStore(host); val vault = MemoryVault(); val store = SyncStore(host, received, vault)
        return Client(host, RoomRecordRepository(host), vault,
            SyncEngine(vault, provider, store, received, MediaTransfer(store, File(root, "transfer")), MemoryVault(), TestSyncPreferences()), store, received)
    }
    @After fun cleanup() = runBlocking { hosts.forEach { it.close() }; roots.forEach { it.deleteRecursively() }; Unit }
    private fun requireBackend() = assumeTrue("Set syncBaseUrl for the local backend", InstrumentationRegistry.getArguments().containsKey("syncBaseUrl"))
    private suspend fun create(client: Client) {
        client.engine.connect(base, ConnectionMethod.CREATE, "", "Android test")
        assertNull(client.engine.state.value.problem); assertNotNull(client.vault.load())
        assertFalse(client.engine.state.value.enabled); client.engine.setEnabled(true)
    }
    private suspend fun recover(client: Client, original: Client) {
        client.engine.connect(base, ConnectionMethod.RECOVER, requireNotNull(original.vault.load()?.recoveryKey), "Android peer")
        assertNull(client.engine.state.value.problem)
        client.engine.setEnabled(true)
    }
    private fun id(table: String): String = when (table) {
        "reminder_completions" -> "reminders:2023-11-14"
        "food_day_records" -> "food-day:$timestamp"
        else -> table
    }
    private fun fields(table: String): JsonObject = buildJsonObject {
        val contract = EntityContracts.registry.entity(table)
        for (field in contract.fields) {
            if (field.name in setOf("id", "created_at", "updated_at", "deleted_at", "device_id", "server_version") || field.derived || field.binary) continue
            val dependency = contract.dependencies.firstOrNull { it.field == field.name }
            put(field.name, when {
                dependency != null -> JsonPrimitive(id(dependency.table))
                field.name == "mime_type" -> JsonPrimitive("image/jpeg")
                field.nullable -> JsonNull
                field.default != null -> field.default!!
                field.enumValues.isNotEmpty() -> JsonPrimitive(field.enumValues.first())
                field.name == "date_key" -> JsonPrimitive("2023-11-14")
                field.jsonText -> JsonPrimitive(if (field.name == "stored_value_effects") "{}" else "[]")
                field.type == "int" -> JsonPrimitive(if (field.name.endsWith("_at") || field.name == "timestamp_ms") timestamp else 1L)
                field.type == "real" -> JsonPrimitive(1.25)
                field.type == "bool" -> JsonPrimitive(true)
                else -> JsonPrimitive("Android $table")
            })
        }
    }
    private suspend fun mixed(client: Client) {
        val objectBytes = ByteArray(65_537) { (it % 251).toByte() }
        val ref = client.host.access { MediaObjects(client.host.mediaDirectory).capture(objectBytes, "image/jpeg") }
        client.records.save(EntityContracts.registry.entities.keys.map { table ->
            val snapshot = fields(table).toMutableMap()
            if (table == "media_assets") { snapshot["checksum"] = JsonPrimitive(ref.checksum); snapshot["byte_count"] = JsonPrimitive(ref.size); snapshot["storage_key"] = JsonPrimitive(ref.key) }
            val binary = EntityContracts.registry.entity(table).fields.filter { it.binary && it.name != "inline_data" }
                .associate { it.name to ByteArray(if (table == "media_attachments") 3 * 1024 * 1024 else 4096) { i -> (i % 127).toByte() } }
            RecordChange(table, id(table), JsonObject(snapshot).toString(), timestamp, client.vault.localDeviceId, binaryFields = binary)
        })
    }
    private suspend fun edit(client: Client, table: String, field: String, value: String, time: Long = timestamp + 1) {
        client.records.save(listOf(RecordChange(table, id(table), buildJsonObject { put(field, value) }.toString(), time, client.vault.localDeviceId)))
    }
    private fun assertSynced(client: Client, result: Boolean) {
        assertTrue("Sync failed: ${client.engine.state.value.problem}, pending=${client.engine.state.value.pending}, inbox=${client.engine.state.value.waitingRows}, media=${client.engine.state.value.pendingMedia}", result)
    }

    @Test fun allTwentyOneEntitiesAndMediaRoundTripThroughRealBackend() = runBlocking {
        requireBackend()
        val a = client(); mixed(a); create(a); assertSynced(a, a.engine.sync())
        val b = client(); recover(b, a); assertSynced(b, b.engine.sync())
        for (table in EntityContracts.registry.entities.keys) {
            val first = requireNotNull(a.records.find(table, id(table)))
            val second = requireNotNull(b.records.find(table, id(table)))
            assertEquals("Snapshot mismatch: $table", Json.parseToJsonElement(first.snapshotJson), Json.parseToJsonElement(second.snapshotJson))
        }
        val bytes = b.host.access { PhysicalRecords.binary(it.openHelper.writableDatabase, "media_attachments", "media_attachments", "data") }
        assertEquals(3 * 1024 * 1024, bytes!!.size)
        assertEquals(126.toByte(), bytes[126])
        assertNotEquals(a.vault.localDeviceId, a.vault.load()!!.serverDevice)
        assertSynced(a, a.engine.sync()) // Repeated empty pulls must not reuse incompatible page bodies.
        b.records.save(listOf(RecordChange("diary_tags", "diary_tags", "{}", timestamp + 5, b.vault.localDeviceId, deleted = true)))
        assertSynced(b, b.engine.sync()); assertSynced(a, a.engine.sync())
        assertNotEquals(JsonNull, Json.parseToJsonElement(a.records.find("diary_tags", "diary_tags")!!.snapshotJson).jsonObject["deleted_at"])
    }

    @Test fun lostReceiptIsRetriedWithoutClearingNewerEditAndConflictsResolveBothWays() = runBlocking {
        requireBackend()
        var lose = true
        lateinit var a: Client
        val provider = object : SyncApiProvider by factory {
            override fun create(endpoint: String, token: String?): SyncApi {
                val api = factory.create(endpoint, token)
                return object : SyncApi by api {
                    override suspend fun post(path: String, body: JsonObject): ByteArray {
                        val response = api.post(path, body)
                        if (path == "api/sync/push" && lose) {
                            lose = false
                            edit(a, "diary_tags", "name", "newer local edit", timestamp + 2)
                            throw IOException("Simulated lost receipt")
                        }
                        return response
                    }
                }
            }
        }
        a = client(provider)
        a.records.save(listOf(RecordChange("diary_tags", "diary_tags", """{"name":"first"}""", timestamp, a.vault.localDeviceId)))
        create(a); assertFalse(a.engine.sync())
        assertTrue(a.records.find("diary_tags", "diary_tags")!!.snapshotJson.contains("newer local edit"))
        a.host.close() // Reopen Room while the frozen request and newer local edit survive.
        assertSynced(a, a.engine.sync())
        val b = client(); recover(b, a); assertSynced(b, b.engine.sync())
        edit(a, "diary_tags", "name", "A"); edit(b, "diary_tags", "name", "B")
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        assertEquals(1, b.engine.state.value.conflicts.size)
        var conflict = b.engine.state.value.conflicts.single()
        b.engine.resolve(conflict.table, conflict.id, conflict.remoteVersion, false)
        assertTrue(b.records.find("diary_tags", "diary_tags")!!.snapshotJson.contains("\"A\""))
        edit(a, "diary_tags", "name", "A2", timestamp + 3); edit(b, "diary_tags", "name", "B2", timestamp + 3)
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        conflict = b.engine.state.value.conflicts.single()
        b.engine.resolve(conflict.table, conflict.id, conflict.remoteVersion, true)
        assertSynced(b, b.engine.sync()); assertSynced(a, a.engine.sync())
        assertTrue(a.records.find("diary_tags", "diary_tags")!!.snapshotJson.contains("B2"))
    }

    @Test fun malformedResponseIsDurableAndStaleSessionsCannotWrite() = runBlocking {
        requireBackend()
        val a = client(); create(a)
        val c = a.vault.load()!!; val api = factory.create(c.endpoint, c.token)
        val caps = Capabilities.decode(decodeObject(api.get("api/sync/capabilities")), c.account, a.store.contracts)
        val session = a.store.prepare(c, caps)
        a.received.persistRaw(session.scope, session.databaseToken, "broken", "not-json".toByteArray())
        a.host.close()
        val raw = a.host.access { it.records().unreceivedPages(session.scope).single().body }
        assertEquals("not-json", raw.decodeToString())
        try { a.store.access(session) { }; fail("Old session accepted") } catch (_: IllegalStateException) { }
        assertEquals(0L, a.host.access { it.records().target(session.scope)!!.receivedCursor })
    }

    @Test fun keystoreCiphertextExcludesPlainCredentialsAndSurvivesRecreation() {
        val name = "sync-vault-test-${UUID.randomUUID()}"
        val vault = CredentialVault(context, name)
        val value = Credentials("id", "https://example.com/", "account", "device", "synthetic-secret", "synthetic-recovery")
        try {
            vault.save(value)
            assertEquals(value, CredentialVault(context, name).load())
            val ciphertext = File(context.noBackupFilesDir, name).readText()
            assertFalse(ciphertext.contains(value.token)); assertFalse(ciphertext.contains(value.recoveryKey!!))
            assertEquals(vault.localDeviceId, CredentialVault(context, name).localDeviceId)
        } finally { vault.save(null) }
    }

    @Test fun accountReplacementBacksUpAndDoesNotUploadPreviousAccountsRecords() = runBlocking {
        requireBackend()
        val a = client(); create(a)
        a.records.save(listOf(RecordChange("diary_tags", "private-old", """{"name":"old account"}""", timestamp, a.vault.localDeviceId)))
        assertSynced(a, a.engine.sync()); a.engine.disconnect()
        a.engine.connect(base, ConnectionMethod.CREATE, "", "new account", false)
        assertEquals(SyncProblem.ACCOUNT_REPLACEMENT, a.engine.state.value.problem)
        a.engine.connect(base, ConnectionMethod.CREATE, "", "new account", true)
        a.engine.setEnabled(true)
        assertNull(a.engine.state.value.problem); assertNull(a.records.find("diary_tags", "private-old"))
        assertSynced(a, a.engine.sync())
        val c = a.vault.load()!!
        val rows = decodeObject(factory.create(c.endpoint, c.token).get("api/rows/diary_tags"))["rows"]?.takeIf { it != JsonNull }?.jsonArray.orEmpty()
        assertTrue(rows.isEmpty())
    }

    @Test fun fixedWatermarkBootstrapResumesAfterInterruptionAndThenReceivesNewerRows() = runBlocking {
        requireBackend()
        val a = client(); create(a)
        a.records.save((1..505).map { index -> RecordChange("diary_tags", "tag-$index", """{"name":"tag $index"}""", timestamp, a.vault.localDeviceId) })
        if (!a.engine.sync()) assertSynced(a, a.engine.sync())
        var interrupted = false
        val provider = object : SyncApiProvider by factory {
            override fun create(endpoint: String, token: String?): SyncApi {
                val api = factory.create(endpoint, token)
                return object : SyncApi by api {
                    override suspend fun get(path: String, query: Map<String, String>): ByteArray {
                        if (path.endsWith("bootstrap") && query["after"] != "0" && !interrupted) {
                            interrupted = true; throw IOException("Interrupted bootstrap")
                        }
                        return api.get(path, query)
                    }
                }
            }
        }
        val b = client(provider); recover(b, a); assertFalse(b.engine.sync())
        val target = b.host.access { it.records().target(it.records().activeScope()!!)!! }
        assertEquals(500L, target.bootstrapAfter); assertEquals(505L, target.bootstrapWatermark); assertFalse(target.bootstrapComplete)
        a.records.save(listOf(RecordChange("diary_tags", "later", """{"name":"after watermark"}""", timestamp + 1, a.vault.localDeviceId)))
        assertSynced(a, a.engine.sync()); b.host.close(); assertSynced(b, b.engine.sync())
        assertNotNull(b.records.find("diary_tags", "later"))
        val final = b.host.access { it.records().target(it.records().activeScope()!!)!! }
        assertEquals(506L, final.appliedCursor); assertEquals(505L, final.bootstrapWatermark)
    }

    @Test fun fullConflictBatchesDoNotStarvePullAndUnknownFieldsSurviveEditing() = runBlocking {
        requireBackend()
        val a = client(); create(a)
        a.records.save((1..45).map { RecordChange("diary_tags", "conflict-$it", """{"name":"original"}""", timestamp, a.vault.localDeviceId) })
        assertSynced(a, a.engine.sync())
        val b = client(); recover(b, a); assertSynced(b, b.engine.sync())
        for (client in listOf(a, b)) client.records.save((1..45).map {
            RecordChange("diary_tags", "conflict-$it", """{"name":"${if (client == a) "remote" else "local"}"}""", timestamp + 1, client.vault.localDeviceId)
        })
        assertSynced(a, a.engine.sync())
        val connection = a.vault.load()!!; val api = factory.create(connection.endpoint, connection.token)
        val caps = Capabilities.decode(decodeObject(api.get("api/sync/capabilities")), connection.account, a.store.contracts)
        val item = buildJsonObject {
            put("table", "diary_tags"); put("id", "future"); put("operation", "insert"); put("mutationId", UUID.randomUUID().toString())
            put("baseVersion", 0); put("dataVersion", 1); put("updatedAtMs", timestamp); put("deviceId", "future-client")
            put("snapshot", buildJsonObject { put("id", "future"); put("name", "future"); put("created_at", timestamp); put("updated_at", timestamp)
                put("device_id", "future-client"); put("future_field", buildJsonObject { put("value", 9007199254740993L) }) })
        }
        SyncProtocol.receipts(decodeObject(api.post("api/sync/push", buildJsonObject { put("protocolVersion", 2); put("generation", caps.generation); put("items", JsonArray(listOf(item))) })), listOf(item))
        assertSynced(b, b.engine.sync()); assertEquals(45, b.engine.state.value.conflicts.size)
        b.records.save(listOf(RecordChange("diary_tags", "future", """{"name":"edited on Android"}""", timestamp + 10, b.vault.localDeviceId)))
        assertSynced(b, b.engine.sync()); assertSynced(a, a.engine.sync())
        val snapshot = Json.parseToJsonElement(a.records.find("diary_tags", "future")!!.snapshotJson).jsonObject
        assertEquals(9007199254740993L, snapshot["future_field"]!!.jsonObject.integer("value"))
    }

    @Test fun oldMediaDownloadCannotReplaceANewerLocalImageAndNullClearsExplicitly() = runBlocking {
        requireBackend()
        val a = client(); mixed(a); create(a); assertSynced(a, a.engine.sync())
        lateinit var b: Client
        var edited = false
        val provider = object : SyncApiProvider by factory {
            override fun create(endpoint: String, token: String?): SyncApi {
                val api = factory.create(endpoint, token)
                return object : SyncApi by api {
                    override suspend fun download(key: String, file: File, limit: Long) {
                        api.download(key, file, limit)
                        if (limit == 3L * 1024 * 1024 && !edited) {
                            edited = true
                            b.records.save(listOf(RecordChange("media_attachments", "media_attachments", "{}", timestamp + 2, b.vault.localDeviceId,
                                binaryFields = mapOf("data" to byteArrayOf(9, 8, 7)))))
                        }
                    }
                }
            }
        }
        b = client(provider); recover(b, a); assertFalse(b.engine.sync())
        assertTrue(edited)
        assertArrayEquals(byteArrayOf(9, 8, 7), b.host.access { PhysicalRecords.binary(it.openHelper.writableDatabase, "media_attachments", "media_attachments", "data") })
        assertSynced(b, b.engine.sync()); assertSynced(a, a.engine.sync())
        assertArrayEquals(byteArrayOf(9, 8, 7), a.host.access { PhysicalRecords.binary(it.openHelper.writableDatabase, "media_attachments", "media_attachments", "data") })
        val field = EntityContracts.registry.entity("bills").fields.first { it.binary && it.nullable }.name
        a.records.save(listOf(RecordChange("bills", "bills", "{}", timestamp + 3, a.vault.localDeviceId, binaryFields = mapOf(field to null))))
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        assertNull(b.host.access { PhysicalRecords.binary(it.openHelper.writableDatabase, "bills", "bills", field) })
    }

    @Test fun notificationFailureLeavesDurableJobWithoutUndoingData() = runBlocking {
        val a = client()
        a.host.access { it.records().notify(NotificationJob("reminders", "reminder", 1)) }
        assertEquals(0, NotificationDelivery(a.host) { throw IOException("permission unavailable") }.drain())
        assertEquals(1, a.host.access { it.records().notifications().size })
        assertEquals(1, NotificationDelivery(a.host) { true }.drain())
        assertTrue(a.host.access { it.records().notifications().isEmpty() })
    }

    @Test fun ownerIssuesSingleUsePairingCodeAndPairedDeviceUsesSameAccount() = runBlocking {
        requireBackend()
        val owner = client(); create(owner)
        owner.engine.generatePairingCode()
        val code = requireNotNull(owner.engine.state.value.pairingCode)
        assertEquals(10, code.length)
        val paired = client()
        paired.engine.connect(base, ConnectionMethod.PAIR, code, "paired Android")
        paired.engine.setEnabled(true)
        assertNull(paired.engine.state.value.problem)
        assertEquals(owner.vault.load()!!.account, paired.vault.load()!!.account)
        assertFalse(paired.engine.state.value.connection!!.canPair)
        assertSynced(paired, paired.engine.sync())
        val third = client()
        third.engine.connect(base, ConnectionMethod.PAIR, code, "cannot reuse")
        assertEquals(SyncProblem.AUTHENTICATION, third.engine.state.value.problem)
    }

    @Test fun offlineConsumptionAndCompletionFactsConvergeWithoutDuplicateEffects() = runBlocking {
        requireBackend()
        val a = client()
        fun patch(table: String, values: Map<String, JsonElement>) = JsonObject(fields(table) + values).toString()
        a.records.save(listOf(
            RecordChange("stored_value_accounts", "stored_value_accounts", fields("stored_value_accounts").toString(), timestamp, a.vault.localDeviceId),
            RecordChange("stored_value_adjustments", "opening", patch("stored_value_adjustments", mapOf("amount_cents" to JsonPrimitive(5000))), timestamp, a.vault.localDeviceId),
            RecordChange("reminders", "reminders", patch("reminders", mapOf("image_asset_id" to JsonNull, "schedule_kind" to JsonPrimitive("continuousDays"),
                "continuous_days" to JsonPrimitive(2), "imported_completed_days" to JsonPrimitive(0))), timestamp, a.vault.localDeviceId)
        ))
        create(a); assertSynced(a, a.engine.sync())
        val b = client(); recover(b, a); assertSynced(b, b.engine.sync())
        for ((index, client) in listOf(a, b).withIndex()) {
            val amount = (index + 1) * 100
            val date = "2023-11-${15 + index}"
            client.records.save(listOf(
                RecordChange("bills", "offline-bill-$index", patch("bills", mapOf("image_asset_id" to JsonNull,
                    "transaction_type" to JsonPrimitive("storedValueUse"), "final_amount_cents" to JsonPrimitive(amount),
                    "original_amount_cents" to JsonPrimitive(0), "stored_value_effects" to JsonPrimitive("""{"stored_value_accounts":-${amount}}"""))), timestamp + index + 1, client.vault.localDeviceId),
                RecordChange("reminder_completions", "reminders:$date", patch("reminder_completions", mapOf("date_key" to JsonPrimitive(date))), timestamp + index + 1, client.vault.localDeviceId)
            ))
        }
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync()); assertSynced(a, a.engine.sync())
        val before = b.host.access { db -> db.openHelper.writableDatabase.query("SELECT count(*) FROM local_changes").use { it.moveToFirst(); it.getLong(0) } }
        assertSynced(b, b.engine.sync())
        for (client in listOf(a, b)) {
            val account = Json.parseToJsonElement(client.records.find("stored_value_accounts", "stored_value_accounts")!!.snapshotJson).jsonObject
            assertEquals(4700L, account.integer("balance_cents"))
            val reminder = Json.parseToJsonElement(client.records.find("reminders", "reminders")!!.snapshotJson).jsonObject
            assertEquals(2, Json.parseToJsonElement(reminder.text("completion_records")).jsonArray.size)
            assertNotEquals(JsonNull, reminder["completed_at"])
        }
        val after = b.host.access { db -> db.openHelper.writableDatabase.query("SELECT count(*) FROM local_changes").use { it.moveToFirst(); it.getLong(0) } }
        assertEquals(before, after)
    }
    @Test fun disabledSyncMakesNoRequestsAndCancelsAnInFlightRequest() = runBlocking {
        requireBackend()
        val entered = CompletableDeferred<Unit>()
        var block = false
        var calls = 0
        val provider = object : SyncApiProvider by factory {
            override fun create(endpoint: String, token: String?): SyncApi {
                val api = factory.create(endpoint, token)
                return object : SyncApi by api {
                    override suspend fun get(path: String, query: Map<String, String>): ByteArray {
                        calls++
                        if (block) { entered.complete(Unit); awaitCancellation() }
                        return api.get(path, query)
                    }
                }
            }
        }
        val client = client(provider)
        client.engine.connect(base, ConnectionMethod.CREATE, "", "off by default")
        assertFalse(client.engine.state.value.enabled)
        val before = calls
        assertTrue(client.engine.sync()); assertEquals(before, calls)
        client.engine.setEnabled(true); block = true
        val running = launch { client.engine.sync() }
        withTimeout(5_000) { entered.await(); client.engine.setEnabled(false); running.join() }
        assertTrue(running.isCancelled); assertFalse(client.engine.state.value.running); assertFalse(client.engine.state.value.enabled)
        val stopped = calls
        client.engine.sync(false); assertEquals(stopped, calls)
        block = false
        client.engine.setEnabled(true); assertSynced(client, client.engine.sync())
        assertNotNull(client.engine.state.value.lastSuccessAt)
    }

    @Test fun deviceListingAndOwnerRevocationInvalidatePairedCredentials() = runBlocking {
        requireBackend()
        val owner = client(); create(owner)
        owner.engine.generatePairingCode()
        val paired = client()
        paired.engine.connect(base, ConnectionMethod.PAIR, owner.engine.state.value.pairingCode!!, "check-in peer")
        owner.engine.refreshDevices()
        assertEquals(2, owner.engine.state.value.devices.size)
        owner.engine.revokeDevice(paired.vault.load()!!.serverDevice)
        assertNull(owner.engine.state.value.problem); assertEquals(1, owner.engine.state.value.devices.size)
        paired.engine.setEnabled(true)
        assertFalse(paired.engine.sync()); assertEquals(SyncProblem.AUTHENTICATION, paired.engine.state.value.problem)
        assertFalse(paired.engine.state.value.enabled)
    }

    @Test fun syncChoiceIsScopedToAccountAndSurvivesReconnect() = runBlocking {
        requireBackend()
        val a = client(); create(a)
        val recovery = a.vault.load()!!.recoveryKey!!
        a.engine.setEnabled(false); a.engine.disconnect()
        a.engine.connect(base, ConnectionMethod.RECOVER, recovery, "same account")
        assertFalse(a.engine.state.value.enabled)
        a.engine.setEnabled(true); a.engine.disconnect()
        a.engine.connect(base, ConnectionMethod.RECOVER, recovery, "same account again")
        assertTrue(a.engine.state.value.enabled)
        a.engine.disconnect()
        a.engine.connect(base, ConnectionMethod.CREATE, "", "different account", true)
        assertFalse(a.engine.state.value.enabled)
    }

    @Test fun sameDayReminderConflictAndUndoConvergeWithoutDeletingTask() = runBlocking {
        requireBackend()
        val a = client()
        val clock = timestamp + 2 * 86_400_000
        fun reminders(client: Client) = imehc.recoow.core.data.reminders.RoomRemindersRepository(client.host, client.vault.localDeviceId,
            { clock }, { java.time.ZoneOffset.UTC })
        val first = reminders(a)
        first.save(imehc.recoow.core.domain.reminders.ReminderDraft(id = "shared-check-in", title = "Read", scheduledAt = timestamp))
        create(a); assertSynced(a, a.engine.sync())
        val b = client(); recover(b, a); assertSynced(b, b.engine.sync())
        val second = reminders(b)
        val day = java.time.LocalDate.of(2023, 11, 14)
        first.makeUp("shared-check-in", setOf(day), "first device")
        second.makeUp("shared-check-in", setOf(day), "second device")
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        val conflict = b.engine.state.value.conflicts.single { it.table == "reminder_completions" }
        b.engine.resolve(conflict.table, conflict.id, conflict.remoteVersion, false)
        assertSynced(b, b.engine.sync())
        assertEquals("first device", second.reminders.first().single().completions.single().note)
        first.deleteCompletion("shared-check-in", day)
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        assertTrue(first.reminders.first().single().dates.isEmpty())
        assertTrue(second.reminders.first().single().dates.isEmpty())
    }

}
