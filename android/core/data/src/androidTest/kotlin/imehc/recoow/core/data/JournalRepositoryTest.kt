package imehc.recoow.core.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.*
import imehc.recoow.core.data.journal.*
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.items.*
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.*
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

class JournalRepositoryTest {
    private lateinit var root: File; private lateinit var host: DatabaseHost
    private lateinit var media: RoomMediaRepository; private lateinit var records: RoomRecordRepository
    private lateinit var diary: RoomDiaryRepository; private lateinit var food: RoomFoodRepository; private lateinit var items: RoomItemsRepository
    private val zone = ZoneId.of("Asia/Shanghai"); private val day = LocalDate.of(2026, 10, 30)
    private val timestamp = day.atTime(12, 34, 56).atZone(zone).toInstant().toEpochMilli()
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "journal-test-${UUID.randomUUID()}").apply { mkdirs() }
        val live = File(root, "live")
        host = DatabaseHost(context, live, LocalRollbackFiles(live, File(root, "rollback"), object : BackupPreferences {
            override suspend fun capture() = "{}"; override suspend fun restore(json: String) = Unit
        }, "journal-tests"))
        media = RoomMediaRepository(host, File(root, "cache"), "test"); records = RoomRecordRepository(host)
        diary = RoomDiaryRepository(host, media, "test", context, { timestamp }, { zone }); food = RoomFoodRepository(host, media, "test"); items = RoomItemsRepository(host, "test")
    }
    @After fun cleanup() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private fun photo(): PreparedMedia {
        val file = File(root, "${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle()
        return PreparedMedia(file, "image/jpeg", 80, 60)
    }
    private suspend fun row(table: String, id: String) = Json.parseToJsonElement(requireNotNull(records.find(table, id)).snapshotJson).jsonObject
    private suspend fun seed(table: String, id: String, patch: JsonObject = buildJsonObject {}) {
        val fields = buildJsonObject { for (f in EntityContracts.registry.entity(table).fields) {
            if (f.derived || f.binary || f.name in setOf("id", "created_at", "updated_at", "deleted_at", "device_id", "server_version")) continue
            put(f.name, when { f.nullable -> JsonNull; f.default != null -> f.default!!; f.enumValues.isNotEmpty() -> JsonPrimitive(f.enumValues.first())
                f.jsonText -> JsonPrimitive("[]"); f.type == "int" -> JsonPrimitive(timestamp); f.type == "real" -> JsonPrimitive(1.0); f.type == "bool" -> JsonPrimitive(true); else -> JsonPrimitive("Seed") })
        } }
        records.save(listOf(RecordChange(table, id, JsonObject(fields + patch).toString(), timestamp, "seed")))
    }
    @Test fun diaryTagsAndSixSourceSnapshotsSurviveDeletionWithoutRewritingHistory() = runBlocking {
        diary.saveTag(NamedRecordDraft(id = "tag", name = "Trip"))
        val links = DiarySource.entries.map { DiaryLink(it.wire, "source-${it.wire}", "Historical title", "Historical summary", it.icon, timestamp, "{\"id\":\"source\"}") }
        diary.save(DiaryDraft(id = "diary", content = "Long text\n".repeat(10_000), tags = listOf(DiaryTagReference("tag", "Trip")), links = links), "Diary")
        val entry = diary.snapshot.first().entries.single(); assertEquals(100_000, entry.content.length + 1)
        assertEquals(6, entry.links.size); assertEquals("Diary", entry.title)
        val before = row("diary_entries", "diary")
        val tag = diary.snapshot.first().tags.single(); diary.deleteTag(tag.id, tag.revision)
        val snapshot = diary.snapshot.first(); assertEquals(before, row("diary_entries", "diary")); assertEquals("Trip", snapshot.resolvedTags(snapshot.entries.single()).single().value)
        assertEquals(links.toSet(), snapshot.entries.single().links.toSet())
        diary.delete("diary", snapshot.entries.single().revision)
        assertTrue(diary.snapshot.first().entries.isEmpty())
        assertTrue(host.access { it.records().snapshots("diary_links").all { row -> Json.parseToJsonElement(row.json).jsonObject["deleted_at"] != JsonNull } })
    }
    @Test fun diaryOwnerLinksAndAttachmentsRollbackTogetherAndChildChangesRejectStaleEditors() = runBlocking {
        val image = photo(); val attachment = MediaAttachment(id = "photo", kind = AttachmentKind.PHOTO, mimeType = image.mimeType, content = image)
        val missing = attachment.copy(id = "invalid", content = null, assetId = "missing")
        val draft = DiaryDraft(id = "diary", title = "Atomic", attachments = listOf(attachment, missing), links = listOf(DiaryLink("bill", "bill", "Bill snapshot", icon = "receipt.fill")))
        assertThrows(IllegalStateException::class.java) { runBlocking { diary.save(draft, "Diary") } }
        assertNull(records.find("diary_entries", "diary")); assertTrue(host.access { it.records().snapshots("diary_links").isEmpty() }); assertTrue(image.file.exists())
        diary.save(draft.copy(attachments = listOf(attachment)), "Diary")
        val old = diary.snapshot.first().entries.single()
        records.save(listOf(RecordChange("media_attachments", "photo", "{\"title\":\"Remote title\"}", timestamp + 1, "remote")))
        assertThrows(StaleRecordException::class.java) { runBlocking { diary.save(DiaryDraft.from(old).copy(title = "Stale"), "Diary") } }
        assertEquals("Atomic", diary.snapshot.first().entries.single().title)
        val current = diary.snapshot.first().entries.single(); diary.save(DiaryDraft.from(current).copy(content = "Edited"), "Diary")
        assertEquals(MediaAvailability.READY, media.resolve(diary.snapshot.first().entries.single().attachments.single().locator).availability)
    }
    @Test fun foodCopiesClearBillsAndMediaWhileWholeDayDeletePreservesBillsAndOtherDays() = runBlocking {
        seed("bills", "bill1"); seed("bills", "bill2")
        val image = photo()
        food.save(FoodDraft(id = "food", title = "Noodles", occurredAt = timestamp, portion = "Bowl", note = "No salt", billIds = listOf("bill1", "bill1", "bill2"),
            attachments = listOf(MediaAttachment(id = "food-photo", kind = AttachmentKind.PHOTO, mimeType = image.mimeType, content = image))))
        val original = food.snapshot.first().entries.single(); assertEquals(listOf("bill1", "bill2"), original.billIds)
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli(); food.saveDayTitle(dayStart, "Friday", null)
        assertNotNull(records.find("food_day_records", foodDayId(dayStart)))
        food.duplicate(mapOf(original.id to original.revision), day.plusDays(1), zone)
        val copy = food.snapshot.first().entries.single { it.id != original.id }
        assertTrue(copy.billIds.isEmpty()); assertTrue(copy.attachments.isEmpty()); assertEquals(original.note, copy.note)
        assertEquals(day.plusDays(1).atTime(12, 34, 56).atZone(zone).toInstant().toEpochMilli(), copy.occurredAt)
        val group = food.snapshot.first().days(zone).single { it.date == day }
        food.deleteDay(day, zone, group.entries.associate { it.id to it.revision }, group.record?.revision)
        assertEquals(listOf(copy.id), food.snapshot.first().entries.map { it.id })
        assertEquals(JsonNull, row("bills", "bill1")["deleted_at"]); assertEquals(JsonNull, row("bills", "bill2")["deleted_at"])
        assertNotEquals(JsonNull, row("media_attachments", "food-photo")["deleted_at"]); assertNotEquals(JsonNull, row("food_day_records", foodDayId(dayStart))["deleted_at"])
    }
    @Test fun legacyBillAssociationAndDayTitleConflictsArePreservedAndStaleDayDeleteIsRejected() = runBlocking {
        seed("bills", "bill"); seed("food_entries", "legacy", buildJsonObject { put("bill_id", "bill"); put("bill_ids_json", "[\"bill\"]"); put("occurred_at", timestamp) })
        assertEquals(listOf("bill"), food.snapshot.first().entries.single().billIds)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli(); food.saveDayTitle(start, "First", null)
        assertThrows(StaleRecordException::class.java) { runBlocking { food.saveDayTitle(start, "Lost update", null) } }
        val group = food.snapshot.first().days(zone).single(); food.save(FoodDraft(id = "new", title = "New meal", occurredAt = timestamp))
        assertThrows(StaleRecordException::class.java) { runBlocking { food.deleteDay(day, zone, group.entries.associate { it.id to it.revision }, group.record?.revision) } }
        assertEquals(2, food.snapshot.first().entries.size); assertEquals("First", food.snapshot.first().dayRecords.single().title)
    }
    @Test fun itemCategoryDeletionAndTextEditsKeepImagesAndOriginalCategoryId() = runBlocking {
        items.saveCategory(NamedRecordDraft(id = "category", name = "Documents"))
        val image = photo(); items.save(ItemDraft(id = "item", title = "Passport", location = "Drawer", categoryId = "category", hasImage = true, imageChanged = true, imageContent = image))
        val original = items.snapshot.first().items.single(); val ref = row("stored_items", "item")["_media"]
        val category = items.snapshot.first().categories.single(); items.deleteCategory(category.id, category.revision)
        assertEquals("category", items.snapshot.first().items.single().categoryId)
        items.save(ItemDraft.from(original).copy(note = "Keep reference"))
        assertEquals(ref, row("stored_items", "item")["_media"]); assertEquals(MediaAvailability.READY, media.resolve(original.image).availability)
        assertThrows(StaleRecordException::class.java) { runBlocking { items.save(ItemDraft.from(original).copy(title = "Stale")) } }
        val current = items.snapshot.first().items.single(); items.save(ItemDraft.from(current).copy(hasImage = false, imageChanged = true, imageAssetId = null))
        assertFalse(items.snapshot.first().items.single().hasImage)
    }
    @Test fun existingAttachmentWithDeletedAssetCanStillBeEditedAndRetainsReference() = runBlocking {
        val asset = media.createImage(photo()); val attachment = MediaAttachment(id = "photo", kind = AttachmentKind.PHOTO, assetId = asset, mimeType = "image/jpeg")
        diary.save(DiaryDraft(id = "diary", title = "Reference", attachments = listOf(attachment)), "Diary")
        records.save(listOf(RecordChange("media_assets", asset, "{}", timestamp + 1, "remote", deleted = true)))
        val current = diary.snapshot.first().entries.single(); diary.save(DiaryDraft.from(current).copy(content = "Still editable"), "Diary")
        assertEquals(asset, diary.snapshot.first().entries.single().attachments.single().assetId)
    }
    @Test fun sixSourceProvidersReadImportedRecordsAndPreserveTheirSnapshotFieldNames() = runBlocking {
        for (source in DiarySource.entries) seed(source.table, "source-${source.wire}")
        seed("reminders", "source-reminder", buildJsonObject {
            put("schedule_kind", "continuousDays"); put("continuous_days", 5); put("imported_completed_days", 2)
        })
        seed("reminder_completions", "source-completion", buildJsonObject {
            put("reminder_id", "source-reminder"); put("date_key", day.toString()); put("completed_at", timestamp)
        })
        val links = DiarySource.entries.map { source -> diary.sources(source).first().single().also {
            assertEquals(source.wire, it.sourceType); assertEquals("source-${source.wire}", it.sourceId); assertTrue(it.title.isNotBlank())
            assertNotNull(it.occurredAt); assertEquals(it.sourceId, Json.parseToJsonElement(it.snapshot!!).jsonObject["id"]!!.jsonPrimitive.content)
        } }
        val track = Json.parseToJsonElement(links.first { it.sourceType == "track" }.snapshot!!).jsonObject
        assertNotNull(track["distanceMeters"]); assertNotNull(track["durationSeconds"])
        val reminder = Json.parseToJsonElement(links.first { it.sourceType == "reminder" }.snapshot!!).jsonObject
        assertEquals(3, reminder["totalCheckInDays"]!!.jsonPrimitive.int)
        assertEquals(5, reminder["progressTotalDays"]!!.jsonPrimitive.int)
        assertTrue(reminder["scheduleTitle"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(reminder["status"]!!.jsonPrimitive.content.isNotBlank())
        diary.save(DiaryDraft(id = "diary", title = "Sources", links = links), "Diary")
        records.save(listOf(RecordChange("reminder_completions", "source-completion", "{}", timestamp + 1, "remote", deleted = true)))
        val updatedReminder = Json.parseToJsonElement(diary.sources(DiarySource.REMINDER).first().single().snapshot!!).jsonObject
        assertEquals(2, updatedReminder["totalCheckInDays"]!!.jsonPrimitive.int)
        for (source in DiarySource.entries) records.save(listOf(RecordChange(source.table, "source-${source.wire}", "{}", timestamp + 1, "remote", deleted = true)))
        assertEquals(links.toSet(), diary.snapshot.first().entries.single().links.toSet())
        DiarySource.entries.forEach { assertTrue(diary.sources(it).first().isEmpty()) }
    }
}
