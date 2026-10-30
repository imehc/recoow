package imehc.recoow.core.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.BackupPreferences
import imehc.recoow.core.backup.LocalRollbackFiles
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MediaRepositoryTest {
    private lateinit var root: File
    private lateinit var host: DatabaseHost
    private lateinit var media: RoomMediaRepository
    private lateinit var records: RoomRecordRepository
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "media-test-${UUID.randomUUID()}").apply { mkdirs() }
        val preferences = object : BackupPreferences {
            override suspend fun capture() = "{}"
            override suspend fun restore(json: String) = Unit
        }
        host = DatabaseHost(context, File(root, "live"), LocalRollbackFiles(File(root, "live"), File(root, "rollback"), preferences, "test"))
        media = RoomMediaRepository(host, File(root, "cache"), "test-device")
        records = RoomRecordRepository(host)
    }
    @After fun cleanup() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private fun image(color: Int): PreparedMedia {
        val file = File(root, "${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle()
        return PreparedMedia(file, "image/jpeg", 120, 80)
    }
    private fun change(table: String, id: String, patch: JsonObject = fixture(table), binary: Map<String, ByteArray?> = emptyMap()) =
        RecordChange(table, id, patch.toString(), System.currentTimeMillis(), "test-device", binaryFields = binary)
    private fun fixture(table: String) = buildJsonObject {
        for (field in EntityContracts.registry.entity(table).fields) {
            if (field.derived || field.binary || field.name in setOf("id", "created_at", "updated_at", "deleted_at", "device_id", "server_version")) continue
            put(field.name, when {
                field.nullable -> JsonNull
                field.default != null -> field.default!!
                field.enumValues.isNotEmpty() -> JsonPrimitive(field.enumValues.first())
                field.name == "date_key" -> JsonPrimitive("2026-10-30")
                field.jsonText -> JsonPrimitive("[]")
                field.type == "int" -> JsonPrimitive(1)
                field.type == "real" -> JsonPrimitive(1.0)
                field.type == "bool" -> JsonPrimitive(true)
                else -> JsonPrimitive("test")
            })
        }
    }

    @Test fun deduplicatesRenamesReplacesAndPreservesHistoryForRollback() = runBlocking {
        val first = image(Color.RED); val second = image(Color.BLUE)
        val id = media.createImage(first, " Original ")
        assertEquals(id, media.createImage(first))
        assertEquals(1, media.assets.first().size)
        media.rename(id, "👩🏽‍💻".repeat(50))
        assertEquals("👩🏽‍💻".repeat(50), media.assets.first().single().title)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { media.rename(id, "x".repeat(51)) } }
        val oldKey = media.assets.first().single().checksum
        media.replaceImage(id, second)
        assertEquals(id, media.assets.first().single().id)
        assertNotEquals(oldKey, media.assets.first().single().checksum)
        assertTrue(File(host.mediaDirectory, "sync/$oldKey").isFile)
        val currentHash = media.assets.first().single().checksum
        try {
            host.guarded("media rollback") { db, _ ->
                db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE media_assets SET title = 'bad'") }
                throw IllegalStateException("force rollback")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(currentHash, media.assets.first().single().checksum)
        assertEquals(MediaAvailability.READY, media.resolve(MediaLocator(id)).availability)
    }

    @Test fun allReferenceKindsPreventDeletionAndTombstonesStopCounting() = runBlocking {
        val asset = media.createImage(image(Color.GREEN))
        val references = mapOf("bills" to "image_asset_id", "reminders" to "image_asset_id", "stored_items" to "image_asset_id",
            "decision_choice_records" to "option_image_asset_id")
        for ((table, field) in references) {
            val fields = JsonObject(fixture(table).toMutableMap().apply { put(field, JsonPrimitive(asset)) })
            records.save(listOf(change(table, table, fields)))
        }
        records.save(listOf(change("decision_collections", "collection")))
        records.save(listOf(change("decision_options", "option", JsonObject(fixture("decision_options").toMutableMap().apply {
            put("collection_id", JsonPrimitive("collection")); put("image_asset_id", JsonPrimitive(asset))
        }))))
        val owner = AttachmentOwner(AttachmentOwnerType.DIARY, "diary")
        media.saveAttachments(owner, listOf(MediaAttachment(kind = AttachmentKind.PHOTO, assetId = asset, mimeType = "image/jpeg")), change("diary_entries", owner.id))
        assertEquals(6, media.assets.first().single().references)
        assertThrows(AssetInUseException::class.java) { runBlocking { media.delete(asset) } }
        media.saveAttachments(owner, emptyList())
        val deletes = references.keys.map { change(it, it, buildJsonObject {}).copy(deleted = true) } + change("decision_options", "option", buildJsonObject {}).copy(deleted = true)
        records.save(deletes)
        assertEquals(0, media.assets.first().single().references)
        media.delete(asset)
        assertTrue(media.assets.first().isEmpty())
    }

    @Test fun attachmentOwnerAndMixedOrderAreAtomicAndDoNotDeleteSharedContent() = runBlocking {
        val asset = media.createImage(image(Color.GREEN))
        val owner = AttachmentOwner(AttachmentOwnerType.FOOD_ENTRY, "food")
        records.save(listOf(change("food_day_records", "day")))
        val ownerFields = fixture("food_entries")
        // Metadata-only existing audio represents received, not-yet-downloaded content.
        val audioId = "audio"
        records.save(listOf(change("food_entries", owner.id, ownerFields), change("media_attachments", audioId,
            JsonObject(fixture("media_attachments").toMutableMap().apply {
                put("owner_type", JsonPrimitive("foodEntry")); put("owner_id", JsonPrimitive(owner.id))
                put("kind", JsonPrimitive("audio")); put("mime_type", JsonPrimitive("audio/mp4"))
            }))))
        val audio = media.attachments(owner).first().single()
        val photo1 = MediaAttachment(kind = AttachmentKind.PHOTO, assetId = asset, mimeType = "image/jpeg")
        val photo2 = MediaAttachment(kind = AttachmentKind.PHOTO, mimeType = "image/jpeg", content = image(Color.RED))
        val list = listOf(photo1, audio, photo2)
        media.saveAttachments(owner, list.movePhoto(photo1.id, 1))
        assertEquals(listOf(photo2.id, audio.id, photo1.id), media.attachments(owner).first().map { it.id })
        val invalid = photo1.copy(id = "invalid", assetId = "missing")
        val before = records.find("food_entries", owner.id)!!.snapshotJson
        assertThrows(IllegalStateException::class.java) { runBlocking {
            media.saveAttachments(owner, listOf(invalid), change("food_entries", owner.id, buildJsonObject { put("note", "must roll back") }))
        } }
        assertEquals(before, records.find("food_entries", owner.id)!!.snapshotJson)
        media.saveAttachments(owner, listOf(audio))
        assertEquals(listOf(audio.id), media.attachments(owner).first().map { it.id })
        assertEquals(MediaAvailability.READY, media.resolve(MediaLocator(asset)).availability)
    }

    @Test fun pendingReferencesSurviveEditsAndCorruptObjectsNeverReachTheDecoder() = runBlocking {
        val id = media.createImage(image(Color.RED))
        val checksum = media.assets.first().single().checksum
        val original = File(host.mediaDirectory, "sync/$checksum")
        val bytes = original.readBytes()
        original.delete()
        assertEquals(MediaAvailability.PENDING, media.resolve(MediaLocator(id)).availability)
        media.rename(id, "Pending title")
        assertEquals("Pending title", media.resolve(MediaLocator(id)).title)
        assertTrue(records.find("media_assets", id)!!.snapshotJson.contains(checksum))
        original.writeBytes(byteArrayOf(1, 2))
        assertEquals(MediaAvailability.DAMAGED, media.resolve(MediaLocator(id)).availability)
        MediaObjects(host.mediaDirectory).capture(bytes, "image/jpeg")
        assertEquals(MediaAvailability.READY, media.resolve(MediaLocator(id)).availability)
    }

    @Test fun legacyBlobIsReadableWithoutChangingTheReferenceAndLargeFieldsUseStreaming() = runBlocking {
        val photo = image(Color.YELLOW)
        records.save(listOf(change("bills", "legacy", binary = mapOf("image_data" to photo.file.readBytes()))))
        host.access { db -> db.withTransaction {
            val row = db.records().snapshot("bills", "legacy")!!
            val withoutReference = JsonObject(Json.parseToJsonElement(row.json).jsonObject.filterKeys { it != "_media" })
            db.records().put(row.copy(json = withoutReference.toString()))
        } }
        val result = media.resolve(MediaLocator(table = "bills", recordId = "legacy"))
        assertEquals(MediaAvailability.READY, result.availability)
        assertArrayEquals(photo.file.readBytes(), result.file!!.readBytes())
        assertFalse(records.find("bills", "legacy")!!.snapshotJson.contains("_media"))
        val large = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        records.save(listOf(change("bills", "large", binary = mapOf("image_data" to large))))
        host.access { db ->
            val copy = File(root, "large-copy")
            assertEquals(large.size.toLong(), PhysicalRecords.copyBinary(db.openHelper.writableDatabase, "bills", "large", "image_data", copy))
            assertArrayEquals(large, copy.readBytes())
        }
    }

    @Test fun cleanupRetainsHistoricBytesAndReorderingRejectsStaleLists() = runBlocking {
        val first = media.createImage(image(Color.RED)); val second = media.createImage(image(Color.BLUE))
        media.reorder(listOf(first, second))
        assertEquals(listOf(first, second), media.assets.first().map { it.id })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { media.reorder(listOf(first)) } }
        val retained = media.assets.first().map { it.checksum }
        media.delete(first)
        val orphan = MediaObjects(host.mediaDirectory).capture("unreferenced".toByteArray(), "audio/mp4")
        File(host.mediaDirectory, "sync").listFiles()!!.forEach { it.setLastModified(1) }
        assertEquals(1, media.collectOrphans())
        assertFalse(MediaObjects(host.mediaDirectory).file(orphan).exists())
        retained.forEach { assertTrue(File(host.mediaDirectory, "sync/$it").exists()) }
    }
}
