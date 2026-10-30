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
        override fun clear() { values.clear(); success.clear() }
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

    @Test fun tracksPageAcrossDevicesPreserveManualSegmentsAndCascadeDeletion() = runBlocking {
        requireBackend(); val a = client(); create(a); val b = client(); recover(b, a)
        suspend fun drain(client: Client) {
            repeat(8) {
                if (client.engine.sync()) return
                assertEquals(SyncProblem.PENDING, client.engine.state.value.problem)
            }
            fail("Paged sync did not drain")
        }
        var clock = System.currentTimeMillis()
        val ar = imehc.recoow.core.data.tracks.RoomTracksRepository(a.host, a.vault.localDeviceId) { clock }
        val br = imehc.recoow.core.data.tracks.RoomTracksRepository(b.host, b.vault.localDeviceId) { clock }
        val session = ar.begin("Paged track", 10)
        val points = (0..1200).map { imehc.recoow.core.domain.tracks.TrackPoint(coordinate = imehc.recoow.core.domain.location.GeoCoordinate(31.23, 121.47 + it * 0.00001),
            time = session.start + it * 1000L, accuracy = 5.0, speed = 1.0) }
        clock += 1_300_000
        ar.checkpoint(session, points, 1200, imehc.recoow.core.domain.tracks.TrackStatus.FINISHED)
        drain(a); drain(b)
        var d = br.detail(session.track.id).first(); assertEquals(points, d.points)
        clock += 1000
        br.editSegment(session.track.id, d.revision, d.segments.single().id, imehc.recoow.core.domain.tracks.SegmentEdit.Split(points[600].time))
        d = br.detail(session.track.id).first()
        clock += 1000
        br.editSegment(session.track.id, d.revision, d.segments.first().id, imehc.recoow.core.domain.tracks.SegmentEdit.Type(imehc.recoow.core.domain.tracks.MotionType.CYCLING))
        drain(b); drain(a)
        ar.ensureSegments(session.track.id); val fromA = ar.detail(session.track.id).first()
        assertEquals(2, fromA.segments.size); assertTrue(fromA.segments.all { it.manual })
        assertEquals(imehc.recoow.core.domain.tracks.MotionType.CYCLING, fromA.segments.first().motion)
        assertEquals(br.detail(session.track.id).first().segments, fromA.segments)
        clock += 1000
        ar.delete(session.track.id, fromA.revision)
        drain(a); drain(b)
        assertTrue(br.tracks.first().isEmpty()); assertTrue(br.detail(session.track.id).first().points.isEmpty()); assertTrue(br.detail(session.track.id).first().segments.isEmpty())
    }

    @Test fun anniversariesAndChoiceSnapshotsRoundTripAfterSourceDeletion() = runBlocking {
        requireBackend(); val a = client(); create(a); val b = client(); recover(b, a)
        val ad = imehc.recoow.core.data.decision.RoomDecisionsRepository(a.host, a.vault.localDeviceId)
        val bd = imehc.recoow.core.data.decision.RoomDecisionsRepository(b.host, b.vault.localDeviceId)
        val aa = imehc.recoow.core.data.anniversaries.RoomAnniversariesRepository(a.host, a.vault.localDeviceId)
        val ba = imehc.recoow.core.data.anniversaries.RoomAnniversariesRepository(b.host, b.vault.localDeviceId)
        ad.saveCollection(imehc.recoow.core.domain.decision.CollectionDraft(id = "choices", title = "Lunch"))
        val file = File(a.host.directory, "option.jpg"); val bitmap = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle()
        ad.saveOption(imehc.recoow.core.domain.decision.OptionDraft(id = "option", collectionId = "choices", title = "Burger", customInfo = "Original", imageChanged = true, hasImage = true,
            imageContent = imehc.recoow.core.domain.media.PreparedMedia(file, "image/jpeg", 40, 30)))
        val choice = ad.choose("choices")
        aa.save(imehc.recoow.core.domain.anniversaries.AnniversaryDraft(id = "date", title = "Lunar", calendar = imehc.recoow.core.domain.calendar.DateCalendar.CHINESE, lead = 1440))
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        val received = bd.snapshot.first().history.single(); assertEquals(choice, received.id); assertEquals("Original", received.customInfo)
        val media = imehc.recoow.core.data.media.RoomMediaRepository(b.host, File(b.host.directory, "cache"), b.vault.localDeviceId)
        assertEquals(imehc.recoow.core.domain.media.MediaAvailability.READY, media.resolve(received.image).availability)
        assertEquals(imehc.recoow.core.domain.calendar.DateCalendar.CHINESE, ba.records.first().single().calendar)
        val collection = ad.snapshot.first().collections.single(); ad.deleteCollection(collection.id, collection.revision)
        val anniversary = aa.records.first().single(); aa.save(imehc.recoow.core.domain.anniversaries.AnniversaryDraft.from(anniversary).copy(enabled = false))
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        assertTrue(bd.snapshot.first().options.isEmpty()); assertEquals(received, bd.snapshot.first().history.single()); assertFalse(ba.records.first().single().enabled)
        assertEquals(imehc.recoow.core.domain.media.MediaAvailability.READY, media.resolve(received.image).availability)
        bd.deleteChoice(received.id, received.revision); assertSynced(b, b.engine.sync()); assertSynced(a, a.engine.sync()); assertTrue(ad.snapshot.first().history.isEmpty())
    }

    @Test fun journalRepositoriesRoundTripMediaReferencesCopiesAndDeletionThroughBackend() = runBlocking {
        requireBackend()
        val a = client(); create(a)
        val b = client(); recover(b, a)
        fun media(c: Client) = imehc.recoow.core.data.media.RoomMediaRepository(c.host, File(c.host.directory, "test-media-cache"), c.vault.localDeviceId)
        val am = media(a); val bm = media(b)
        val ad = imehc.recoow.core.data.journal.RoomDiaryRepository(a.host, am, a.vault.localDeviceId, context)
        val bd = imehc.recoow.core.data.journal.RoomDiaryRepository(b.host, bm, b.vault.localDeviceId, context)
        val af = imehc.recoow.core.data.journal.RoomFoodRepository(a.host, am, a.vault.localDeviceId)
        val bf = imehc.recoow.core.data.journal.RoomFoodRepository(b.host, bm, b.vault.localDeviceId)
        val ai = imehc.recoow.core.data.journal.RoomItemsRepository(a.host, a.vault.localDeviceId)
        val bi = imehc.recoow.core.data.journal.RoomItemsRepository(b.host, b.vault.localDeviceId)
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val file = File(a.host.directory, "journal.jpg")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle()
        val photo = imehc.recoow.core.domain.media.PreparedMedia(file, "image/jpeg", 32, 32)
        val asset = am.createImage(photo)
        ai.saveCategory(imehc.recoow.core.domain.records.NamedRecordDraft(id = "category", name = "Documents"))
        ai.save(imehc.recoow.core.domain.items.ItemDraft(id = "item", title = "Passport", location = "Drawer", categoryId = "category", imageChanged = true, imageAssetId = asset, hasImage = true))
        ad.saveTag(imehc.recoow.core.domain.records.NamedRecordDraft(id = "tag", name = "Travel"))
        val source = ad.sources(imehc.recoow.core.domain.diary.DiarySource.ITEM).first().single()
        val attachment = imehc.recoow.core.domain.media.MediaAttachment(id = "diary-photo", kind = imehc.recoow.core.domain.media.AttachmentKind.PHOTO, mimeType = "image/jpeg", assetId = asset)
        ad.save(imehc.recoow.core.domain.diary.DiaryDraft(id = "diary", title = "Trip", content = "Long content\n".repeat(1000),
            tags = listOf(imehc.recoow.core.domain.diary.DiaryTagReference("tag", "Travel")), links = listOf(source), attachments = listOf(attachment)), "Diary")
        imehc.recoow.core.data.bills.RoomBillsRepository(a.host, a.vault.localDeviceId).save(
            imehc.recoow.core.domain.bills.BillDraft(id = "bills", title = "Meal bill", amounts = imehc.recoow.core.domain.bills.AmountInput(original = "20", final = "20")))
        val zone = java.time.ZoneId.of("Asia/Shanghai"); val day = java.time.LocalDate.of(2026, 10, 30)
        val whenAt = day.atTime(12, 34, 56).atZone(zone).toInstant().toEpochMilli()
        af.save(imehc.recoow.core.domain.food.FoodDraft(id = "food", title = "Noodles", occurredAt = whenAt, billIds = listOf("bills"), attachments = listOf(attachment.copy(id = "food-photo"))))
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli(); af.saveDayTitle(dayStart, "Trip meals", null)
        assertSynced(a, a.engine.sync()); assertSynced(b, b.engine.sync())
        val diary = bd.snapshot.first().entries.single(); assertEquals("Trip", diary.title); assertEquals(source, diary.links.single())
        assertEquals(imehc.recoow.core.domain.media.MediaAvailability.READY, bm.resolve(diary.attachments.single().locator).availability)
        assertEquals(imehc.recoow.core.domain.media.MediaAvailability.READY, bm.resolve(bi.snapshot.first().items.single().image).availability)
        val category = bi.snapshot.first().categories.single(); bi.deleteCategory(category.id, category.revision)
        val tag = bd.snapshot.first().tags.single(); bd.deleteTag(tag.id, tag.revision)
        val oldFood = bf.snapshot.first().entries.single(); bf.duplicate(mapOf(oldFood.id to oldFood.revision), day.plusDays(1), zone)
        val originalDay = bf.snapshot.first().days(zone).single { it.date == day }
        bf.deleteDay(day, zone, originalDay.entries.associate { it.id to it.revision }, originalDay.record?.revision)
        assertSynced(b, b.engine.sync()); assertSynced(a, a.engine.sync())
        assertEquals("category", ai.snapshot.first().items.single().categoryId); assertTrue(ai.snapshot.first().categories.isEmpty())
        assertEquals("Travel", ad.snapshot.first().entries.single().tags.single().value); assertTrue(ad.snapshot.first().tags.isEmpty())
        val copy = af.snapshot.first().entries.single(); assertNotEquals("food", copy.id); assertTrue(copy.billIds.isEmpty()); assertTrue(copy.attachments.isEmpty())
        assertEquals(day.plusDays(1).atTime(12, 34, 56).atZone(zone).toInstant().toEpochMilli(), copy.occurredAt)
        assertEquals(JsonNull, Json.parseToJsonElement(a.records.find("bills", "bills")!!.snapshotJson).jsonObject["deleted_at"])
        assertTrue(af.snapshot.first().dayRecords.isEmpty())
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
    @Test fun backupReplacementUploadsAllEntitiesAndMediaToTheCurrentAccount() = runBlocking {
        requireBackend()
        val source = client(); mixed(source)
        edit(source, "bills", "stored_value_effects", "{\"stored_value_accounts\":1}", timestamp)
        val destination = client(); create(destination)
        val jobs = mutableListOf<Job>()
        fun backup(client: Client): imehc.recoow.core.data.backup.RoomDataManagement {
            val job = SupervisorJob(); jobs += job
            val preferences = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
                File(client.host.directory.parentFile, "backup.preferences_pb")
            }
            return imehc.recoow.core.data.backup.RoomDataManagement(context, client.host, preferences,
                object : DeviceIdentity { override val id = client.vault.localDeviceId })
        }
        try {
            val file = File(source.host.directory.parentFile, "exchange.recoowbackup")
            backup(source).export { file.outputStream() }
            val importer = backup(destination)
            val prepared = importer.preview { file.inputStream() }
            val binding = destination.vault.load()
            destination.engine.localMaintenance {
                assertEquals(21, importer.import(prepared, imehc.recoow.core.domain.backup.ImportMode.REPLACE_ALL, emptySet()))
            }
            importer.discard(prepared)
            assertEquals(binding, destination.vault.load())
            assertSynced(destination, destination.engine.sync())
            val peer = client(); recover(peer, destination); assertSynced(peer, peer.engine.sync())
            for (table in EntityContracts.registry.entities.keys) {
                val expected = Json.parseToJsonElement(source.records.find(table, id(table))!!.snapshotJson).jsonObject
                val actual = Json.parseToJsonElement(peer.records.find(table, id(table))!!.snapshotJson).jsonObject
                assertEquals(table, expected.filterKeys { it != "_media" }, actual.filterKeys { it != "_media" })
            }
            peer.host.access { db ->
                val bytes = PhysicalRecords.binary(db.openHelper.writableDatabase, "media_attachments", id("media_attachments"), "data")!!
                assertArrayEquals(ByteArray(3 * 1024 * 1024) { (it % 127).toByte() }, bytes)
            }
        } finally { jobs.forEach { it.cancelAndJoin() } }
    }

    @Test fun localMaintenanceCancelsNetworkAndClearsCredentialsOnlyAfterSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val provider = object : SyncApiProvider by factory {
            override fun create(endpoint: String, token: String?): SyncApi = object : SyncApi by factory.create(endpoint, token) {
                override suspend fun get(path: String, query: Map<String, String>): ByteArray {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
        }
        val client = client(provider)
        val credentials = Credentials("connection", "http://127.0.0.1:8080/", "account", "device", "token")
        client.vault.value = credentials
        client.engine.setEnabled(true)
        val token = client.host.sessionToken()
        val running = launch { client.engine.sync() }
        withTimeout(5_000) {
            entered.await()
            assertEquals(7, client.engine.localMaintenance {
                assertTrue(cancelled)
                client.host.guarded("maintenance-test") { _, _ -> 7 }
            })
            running.join()
        }
        assertTrue(running.isCancelled)
        assertEquals(credentials, client.vault.load())
        assertThrows(IllegalStateException::class.java) { runBlocking { client.host.access(token) { } } }
        assertThrows(IllegalStateException::class.java) { runBlocking {
            client.engine.localMaintenance(clearCredentials = true) { error("failed restore") }
        } }
        assertEquals(credentials, client.vault.load())
        client.engine.localMaintenance(clearCredentials = true) { }
        assertNull(client.vault.load()); assertNull(client.engine.state.value.connection)
        client.vault.value = credentials; client.engine.refresh(); assertFalse(client.engine.state.value.enabled)
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
