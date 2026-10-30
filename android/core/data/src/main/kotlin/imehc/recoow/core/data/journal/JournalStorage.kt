package imehc.recoow.core.data.journal

import androidx.room.withTransaction
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal fun EntitySnapshot.fields() = Json.parseToJsonElement(json).jsonObject
internal fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
internal fun JsonObject.double(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
internal val JsonObject.live get() = this["deleted_at"] == JsonNull
internal fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
internal fun Long?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
internal fun Double?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
internal fun String.normalized() = trim().takeIf { it.isNotEmpty() }
internal fun JsonObject.array(key: String): JsonArray = text(key)?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: JsonArray(emptyList())
internal fun revision(rows: List<EntitySnapshot>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    rows.sortedWith(compareBy<EntitySnapshot> { it.tableName }.thenBy { it.entityId }).forEach {
        digest.update(it.tableName.toByteArray()); digest.update(0.toByte()); digest.update(it.entityId.toByteArray()); digest.update(0.toByte()); digest.update(it.json.toByteArray())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun List<EntitySnapshot>.owned(type: AttachmentOwnerType, id: String) = filter {
    it.tableName == "media_attachments" && it.fields().let { f -> f.text("owner_type") == type.wire && f.text("owner_id") == id }
}
internal fun List<EntitySnapshot>.attachments(): List<MediaAttachment> = filter { it.fields().live }
    .sortedWith(compareBy<EntitySnapshot> { it.fields().long("sort_order") }.thenBy { it.fields().long("created_at") }.thenBy { it.entityId })
    .map { row -> val f = row.fields(); MediaAttachment(row.entityId,
        AttachmentKind.entries.first { it.wire == f.text("kind") }, f.text("title"), f.text("asset_id"), f.text("mime_type")!!,
        f.long("width")?.toInt(), f.long("height")?.toInt(), f.double("duration_seconds")) }
internal fun EntitySnapshot.named() = NamedRecord(entityId, revision(listOf(this)), fields().text("name")!!, fields().text("note"))

/** Shared transaction helpers; feature repositories still define their own save/delete rules. */
internal class JournalStorage(val host: DatabaseHost, val deviceId: String, val now: () -> Long) {
    val writer = RoomRecordRepository(host)
    fun change(table: String, id: String, fields: JsonObject = buildJsonObject {}, deleted: Boolean = false,
        binary: Map<String, ByteArray?> = emptyMap()) = RecordChange(table, id, fields.toString(), now(), deviceId, deleted, binary)
    fun check(row: EntitySnapshot?, expected: String?, children: List<EntitySnapshot> = emptyList()) {
        if (row == null) { if (expected != null) throw StaleRecordException() }
        else if (!row.fields().live || expected != revision(listOf(row) + children)) throw StaleRecordException()
    }
    suspend fun all(db: RecoowDatabase, tables: List<String>) = tables.flatMap { db.records().snapshots(it) }
    suspend fun saveName(table: String, draft: NamedRecordDraft): String = host.access { db -> db.withTransaction {
        check(db.records().snapshot(table, draft.id), draft.revision)
        require(draft.name.isNotBlank())
        writer.saveInTransaction(db, listOf(change(table, draft.id, buildJsonObject { put("name", draft.name.trim()); put("note", draft.note.normalized().json()) })))
        draft.id
    } }
    suspend fun deleteName(table: String, id: String, expected: String) = host.access { db -> db.withTransaction {
        check(db.records().snapshot(table, id), expected)
        writer.saveInTransaction(db, listOf(change(table, id, deleted = true)))
    } }
    suspend fun deleteOwner(db: RecoowDatabase, type: AttachmentOwnerType, id: String, expected: String,
        links: List<EntitySnapshot> = emptyList()) {
        val attachments = db.records().snapshots("media_attachments").owned(type, id)
        check(db.records().snapshot(type.table, id), expected, attachments + links)
        val children = (attachments + links).filter { it.fields().live }
        writer.saveInTransaction(db, children.map { change(it.tableName, it.entityId, deleted = true) } + change(type.table, id, deleted = true))
    }
}
