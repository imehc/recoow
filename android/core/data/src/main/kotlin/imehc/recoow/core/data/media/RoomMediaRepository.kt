package imehc.recoow.core.data.media

import android.graphics.BitmapFactory
import android.icu.text.BreakIterator
import androidx.room.withTransaction
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomMediaRepository(private val host: DatabaseHost, private val cache: File,
    private val deviceId: String, private val now: () -> Long = System::currentTimeMillis) : MediaRepository {
    private val writer = RoomRecordRepository(host)
    private val objects get() = MediaObjects(host.mediaDirectory)
    private val changes = host.observe { it.records().observeMediaRecords() }

    override val assets = changes.map { rows ->
        val all = rows.map { it.tableName to Json.parseToJsonElement(it.json).jsonObject }.filter { it.second.live }
        all.filter { it.first == "media_assets" && it.second.text("kind") == "image" }
            .sortedWith(compareBy<Pair<String, JsonObject>> { it.second.long("sort_order") }
                .thenByDescending { it.second.long("created_at") }.thenBy { it.second.text("id") })
            .map { (_, row) ->
                val id = row.text("id")!!
                MediaAsset(id, row.text("title"), row.text("mime_type")!!, row.long("byte_count"),
                    row.text("checksum")!!, row.int("width"), row.int("height"), row.long("sort_order"),
                    all.count { (table, value) -> referenceFields[table]?.let { value.text(it) == id } == true })
            }
    }.distinctUntilChanged()

    override fun observeContent(locator: MediaLocator): Flow<ResolvedMedia> = combine(changes,
        host.observe { it.records().observeMediaJobs() }) { _, _ -> Unit }.map { resolve(locator) }.distinctUntilChanged()

    override suspend fun resolve(locator: MediaLocator): ResolvedMedia = host.access { db ->
        locator.draft?.let { return@access ResolvedMedia(if (it.isFile) MediaAvailability.READY else MediaAvailability.MISSING, it) }
        val row = if (locator.table != null && locator.recordId != null) snapshot(db, locator.table!!, locator.recordId!!) else null
        val assetId = locator.assetId ?: row?.text(referenceFields[locator.table] ?: "image_asset_id")
        var assetResult: ResolvedMedia? = null
        if (assetId != null) {
            val asset = snapshot(db, "media_assets", assetId)
            assetResult = if (asset != null && asset.live) resolveRow(db, "media_assets", assetId, "inline_data", asset)
                else ResolvedMedia(MediaAvailability.PENDING)
            if (assetResult.availability == MediaAvailability.READY) return@access assetResult
        }
        val fallback = if (row != null && row.live) resolveRow(db, locator.table!!, locator.recordId!!, locator.field, row) else null
        if (fallback?.availability == MediaAvailability.READY) fallback.copy(title = assetResult?.title ?: fallback.title)
        else assetResult ?: fallback ?: ResolvedMedia(MediaAvailability.MISSING)
    }

    private fun resolveRow(db: RecoowDatabase, table: String, id: String, field: String, row: JsonObject): ResolvedMedia {
        require(EntityContracts.registry.entity(table).fields.any { it.name == field && it.binary })
        val title = row.text("title")
        val refJson = row["_media"]?.jsonObject?.get(if (table == "media_assets") "object" else field)
        if (refJson != null && refJson != JsonNull) {
            val ref = MediaReference.parse(refJson)
            if (objects.available(ref)) return ready(objects.file(ref), ref, title)
            // 已存在的损坏对象既不能返回，也不能当作有效图片解码。
            if (objects.file(ref).exists()) return ResolvedMedia(MediaAvailability.DAMAGED, title = title)
            return ResolvedMedia(MediaAvailability.PENDING, title = title)
        }
        if (table == "media_assets") {
            val key = row.text("storage_key")
            val source = key?.let { File(host.mediaDirectory, it).canonicalFile }
            if (source != null && source.path.startsWith(host.mediaDirectory.canonicalPath + "/") && source.isFile) {
                if (source.length() != row.long("byte_count") || MediaObjects.checksum(source) != row.text("checksum"))
                    return ResolvedMedia(MediaAvailability.DAMAGED, title = title)
                return ready(source, objects.capture(source, row.text("mime_type")!!), title)
            }
        }
        // 把旧版 BLOB 经私有文件中转；缩略图绝不在 CursorWindow 里展开原图。
        cache.mkdirs()
        val temporary = File.createTempFile("legacy-", ".tmp", cache)
        try {
            val size = PhysicalRecords.copyBinary(db.openHelper.writableDatabase, table, id, field, temporary)
            if (size == null || size == 0L) return ResolvedMedia(MediaAvailability.MISSING, title = title)
            val ref = objects.capture(temporary, row.text("mime_type") ?: "image/jpeg")
            if (table == "media_assets" && (ref.checksum != row.text("checksum") || ref.size != row.long("byte_count")))
                return ResolvedMedia(MediaAvailability.DAMAGED, title = title)
            return ready(objects.file(ref), ref, title)
        } finally { temporary.delete() }
    }

    private fun ready(source: File, ref: MediaReference, title: String?): ResolvedMedia {
        cache.mkdirs()
        val copy = File(cache, ref.checksum)
        if (!copy.isFile || copy.length() != ref.size || MediaObjects.checksum(copy) != ref.checksum) {
            val temp = File.createTempFile("resolve-", ".tmp", cache)
            try { source.copyTo(temp, overwrite = true); check(temp.renameTo(copy)) } finally { temp.delete() }
        }
        copy.setLastModified(now())
        return ResolvedMedia(MediaAvailability.READY, copy, title, ref.contentType, ref.checksum)
    }

    override suspend fun createImage(content: PreparedMedia, title: String?): String = host.access { db ->
        db.withTransaction {
            val bytes = imageBytes(content)
            val ref = objects.capture(bytes, content.mimeType)
            val rows = db.records().snapshots("media_assets").map { Json.parseToJsonElement(it.json).jsonObject }.filter { it.live }
            rows.firstOrNull { it.text("kind") == "image" && it.text("checksum") == ref.checksum }?.text("id")
                ?: UUID.randomUUID().toString().also { id ->
                    val order = rows.minOfOrNull { it.long("sort_order") } ?: 0L
                    require(order > Long.MIN_VALUE)
                    val fields = imageFields(content, ref).toMutableMap().apply {
                        put("kind", JsonPrimitive("image")); put("title", titleJson(title)); put("sort_order", JsonPrimitive(order - 1))
                    }
                    writer.saveInTransaction(db, listOf(change("media_assets", id, JsonObject(fields), mapOf("inline_data" to bytes))))
                    // 以不可变对象为准，避免在 SQLite 里再留一份完整副本。
                    db.openHelper.writableDatabase.execSQL("UPDATE media_assets SET inline_data = NULL WHERE id = ?", arrayOf(id))
                }
        }
    }

    override suspend fun rename(id: String, title: String?) = mutate { db ->
        val row = liveAsset(db, id); val value = titleJson(title)
        if (row["title"] != value) writer.saveInTransaction(db, listOf(change("media_assets", id, buildJsonObject { put("title", value) })))
    }
    override suspend fun replaceImage(id: String, content: PreparedMedia) = mutate { db ->
        liveAsset(db, id)
        val bytes = imageBytes(content); val ref = objects.capture(bytes, content.mimeType)
        writer.saveInTransaction(db, listOf(change("media_assets", id, imageFields(content, ref), mapOf("inline_data" to bytes))))
        db.openHelper.writableDatabase.execSQL("UPDATE media_assets SET inline_data = NULL WHERE id = ?", arrayOf(id))
    }
    override suspend fun reorder(ids: List<String>) = mutate { db ->
        require(ids.distinct().size == ids.size)
        val live = db.records().snapshots("media_assets").map { Json.parseToJsonElement(it.json).jsonObject }
            .filter { it.live && it.text("kind") == "image" }.map { it.text("id")!! }.toSet()
        require(ids.toSet() == live) { "Library changed; reload before reordering" }
        writer.saveInTransaction(db, ids.mapIndexed { index, id -> change("media_assets", id, buildJsonObject { put("sort_order", index) }) })
    }
    override suspend fun delete(id: String) = mutate { db ->
        liveAsset(db, id)
        val count = referenceFields.entries.sumOf { (table, field) ->
            db.openHelper.writableDatabase.query("SELECT count(*) FROM $table WHERE deleted_at IS NULL AND $field = ?", arrayOf(id))
                .use { it.moveToFirst(); it.getInt(0) }
        }
        if (count > 0) throw AssetInUseException(count)
        writer.saveInTransaction(db, listOf(change("media_assets", id, buildJsonObject {}, deleted = true)))
    }

    override fun attachments(owner: AttachmentOwner) = host.observe { db -> db.records().observe("media_attachments") }.map { rows ->
        rows.map { Json.parseToJsonElement(it.json).jsonObject }.filter { it.live && it.text("owner_type") == owner.type.wire && it.text("owner_id") == owner.id }
            .sortedWith(compareBy<JsonObject> { it.long("sort_order") }.thenBy { it.long("created_at") }.thenBy { it.text("id") })
            .map { row -> MediaAttachment(row.text("id")!!, AttachmentKind.entries.first { it.wire == row.text("kind") },
                row.text("title"), row.text("asset_id"), row.text("mime_type")!!, row.int("width"), row.int("height"),
                row["duration_seconds"]?.jsonPrimitive?.doubleOrNull) }
    }.distinctUntilChanged()

    override suspend fun saveAttachments(owner: AttachmentOwner, attachments: List<MediaAttachment>, ownerChange: RecordChange?) = mutate { db ->
        saveAttachmentsInTransaction(db, owner, attachments, ownerChange)
    }

    /** 宿主门禁由功能仓库持有，使属主、关联与媒体一起提交或回滚。 */
    internal suspend fun saveAttachmentsInTransaction(db: RecoowDatabase, owner: AttachmentOwner,
        attachments: List<MediaAttachment>, ownerChange: RecordChange? = null) {
        check(db.inTransaction())
        require(owner.id.isNotBlank() && attachments.map { it.id }.distinct().size == attachments.size)
        ownerChange?.let {
            require(it.table == owner.type.table && it.id == owner.id && !it.deleted)
            writer.saveInTransaction(db, listOf(it))
        }
        require(snapshot(db, owner.type.table, owner.id)?.live == true) { "Attachment owner is missing" }
        val existing = db.records().snapshots("media_attachments").map { Json.parseToJsonElement(it.json).jsonObject }
            .filter { it.live && it.text("owner_type") == owner.type.wire && it.text("owner_id") == owner.id }
        attachments.forEachIndexed { index, attachment ->
            val prior = snapshot(db, "media_attachments", attachment.id)
            require(prior == null || (prior.live && prior.text("owner_type") == owner.type.wire && prior.text("owner_id") == owner.id))
            val asset = attachment.assetId?.let {
                require(attachment.kind == AttachmentKind.PHOTO)
                // 仅修改属主或附件顺序时，保留原有的未解析引用。
                if (prior?.text("asset_id") == it) snapshot(db, "media_assets", it)?.takeIf { row -> row.live }
                else liveAsset(db, it)
            }
            val content = attachment.content
            require(content == null || attachment.assetId == null)
            require(prior != null || content != null || attachment.assetId != null)
            val binary: Map<String, ByteArray?> = when {
                content != null -> {
                    require(content.mimeType == attachment.mimeType)
                    val bytes = if (attachment.kind == AttachmentKind.PHOTO) imageBytes(content) else {
                        require(content.mimeType == "audio/mp4" && content.file.length() in 1..64L * 1024 * 1024)
                        require(content.durationSeconds?.let { it.isFinite() && it > 0 } == true)
                        content.file.readBytes()
                    }
                    mapOf("data" to bytes)
                }
                attachment.assetId != null && attachment.assetId != prior?.text("asset_id") -> mapOf("data" to ByteArray(0))
                else -> emptyMap()
            }
            val fields = buildJsonObject {
                put("owner_type", owner.type.wire); put("owner_id", owner.id); put("kind", attachment.kind.wire)
                put("title", titleJson(attachment.title)); put("asset_id", attachment.assetId?.let(::JsonPrimitive) ?: JsonNull)
                put("mime_type", asset?.text("mime_type") ?: attachment.mimeType); put("sort_order", index)
                put("width", (asset?.int("width") ?: content?.width ?: attachment.width)?.let(::JsonPrimitive) ?: JsonNull)
                put("height", (asset?.int("height") ?: content?.height ?: attachment.height)?.let(::JsonPrimitive) ?: JsonNull)
                put("duration_seconds", (content?.durationSeconds ?: attachment.durationSeconds)?.let(::JsonPrimitive) ?: JsonNull)
            }
            // 逐条写入附件，为任意长度的图片或音频列表限定内存占用。
            writer.saveInTransaction(db, listOf(change("media_attachments", attachment.id, fields, binary)))
        }
        val deleted = existing.filter { row -> attachments.none { it.id == row.text("id") } }
            .map { change("media_attachments", it.text("id")!!, buildJsonObject {}, deleted = true) }
        writer.saveInTransaction(db, deleted)
    }

    override suspend fun collectOrphans(): Int = host.access { db ->
        // 保留所有持久化历史，包括已确认的变更和墓碑；其字节可能被
        // 冲突处理、冻结重试和代际重播种用到，回滚另存独立副本。
        val retained = mutableSetOf<String>()
        val hash = Regex("(?:sync/)?[a-f0-9]{64}")
        val columns = mapOf("entity_snapshots" to listOf("json"), "local_changes" to listOf("payloadJson"),
            "sync_outbox" to listOf("payloadJson", "preparedJson"), "sync_rows" to listOf("envelopeJson"),
            "sync_conflicts" to listOf("localJson", "remoteJson"), "sync_inbox" to listOf("envelopeJson"),
            "sync_media_jobs" to listOf("objectKey", "storageKey"))
        for ((table, fields) in columns) for (field in fields) {
            db.openHelper.writableDatabase.query("SELECT $field FROM $table WHERE $field IS NOT NULL").use { cursor ->
                while (cursor.moveToNext()) hash.findAll(cursor.getString(0)).forEach { retained += it.value.removePrefix("sync/") }
            }
        }
        val cutoff = now() - 24 * 60 * 60 * 1000L
        File(host.mediaDirectory, "sync").listFiles().orEmpty().count { file ->
            file.isFile && file.lastModified() < cutoff && file.name !in retained &&
                (file.name.matches(Regex("[a-f0-9]{64}")) || file.name.startsWith("capture-")) && file.delete()
        }
    }

    private suspend fun mutate(action: suspend (RecoowDatabase) -> Unit) = host.access { db -> db.withTransaction { action(db) } }
    private suspend fun snapshot(db: RecoowDatabase, table: String, id: String): JsonObject? {
        EntityContracts.registry.entity(table)
        return db.records().snapshot(table, id)?.json?.let { Json.parseToJsonElement(it).jsonObject }
    }
    private suspend fun liveAsset(db: RecoowDatabase, id: String) = snapshot(db, "media_assets", id)
        ?.also { require(it.live && it.text("kind") == "image") } ?: error("Missing asset")
    private fun change(table: String, id: String, fields: JsonObject, binary: Map<String, ByteArray?> = emptyMap(), deleted: Boolean = false) =
        RecordChange(table, id, fields.toString(), now(), deviceId, deleted, binary)
    private fun imageBytes(content: PreparedMedia): ByteArray {
        require(content.mimeType == "image/jpeg" && content.file.length() in 1..16L * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(content.file.path, bounds)
        require(bounds.outWidth == content.width && bounds.outHeight == content.height && bounds.outMimeType == content.mimeType)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
        return content.file.readBytes()
    }
    private fun imageFields(content: PreparedMedia, ref: MediaReference) = buildJsonObject {
        put("storage_backend", "local"); put("storage_key", ref.key); put("mime_type", content.mimeType)
        put("byte_count", ref.size); put("checksum", ref.checksum)
        put("width", content.width!!); put("height", content.height!!)
    }
    private fun titleJson(raw: String?): JsonElement {
        val title = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return JsonNull
        val iterator = BreakIterator.getCharacterInstance().apply { setText(title) }
        var count = 0; while (iterator.next() != BreakIterator.DONE) count++
        require(count <= 50) { "Title exceeds 50 characters" }
        return JsonPrimitive(title)
    }
    private val JsonObject.live get() = this["deleted_at"] == JsonNull
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    companion object {
        private val referenceFields = mapOf("media_attachments" to "asset_id", "reminders" to "image_asset_id",
            "bills" to "image_asset_id", "stored_items" to "image_asset_id", "decision_options" to "image_asset_id",
            "decision_choice_records" to "option_image_asset_id")
    }
}
