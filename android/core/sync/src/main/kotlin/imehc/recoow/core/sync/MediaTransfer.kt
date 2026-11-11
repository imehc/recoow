package imehc.recoow.core.sync

import androidx.room.withTransaction
import imehc.recoow.core.database.*
import imehc.recoow.core.network.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class MediaTransfer(private val store: SyncStore, private val temporaryDirectory: File) {
    suspend fun transfer(session: SyncSession, api: SyncApi, direction: String, limit: Long): Boolean {
        val jobs = store.access(session) { it.records().media(session.scope, direction) }
        var success = true
        for (job in jobs.filter { it.state == "pending" }) {
            if (job.nextAttemptAt > System.currentTimeMillis()) { success = false; continue }
            val ref = MediaReference(job.objectKey, job.checksum, job.size, job.contentType)
            check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs())
            val temporary = File.createTempFile("transfer-", ".tmp", temporaryDirectory)
            try {
                require(ref.size <= limit)
                if (direction == "upload") {
                    val local = store.access(session) {
                        val objects = MediaObjects(store.host.mediaDirectory)
                        if (objects.available(ref)) { objects.file(ref).copyTo(temporary, overwrite = true); true } else false
                    }
                    if (local) {
                        if (!api.mediaExists(ref.key, ref.checksum, ref.size)) {
                            val response = decodeObject(api.upload(ref.key, ref.checksum, ref.contentType, temporary))
                            require(MediaReference.parse(response) == ref) { "Invalid media receipt" }
                        }
                    } else {
                        // 尚未处理的远端引用，可能在下载完成前就已入队。
                        api.download(ref.key, temporary, ref.size)
                        require(temporary.length() == ref.size && MediaObjects.checksum(temporary) == ref.checksum)
                    }
                    store.access(session) { db ->
                        MediaObjects(store.host.mediaDirectory).install(temporary, ref)
                        db.records().put(job.copy(state = "complete", lastError = null))
                    }
                } else {
                    val local = store.access(session) {
                        val objects = MediaObjects(store.host.mediaDirectory)
                        if (objects.available(ref)) { objects.file(ref).copyTo(temporary, overwrite = true); true } else false
                    }
                    if (!local) api.download(ref.key, temporary, ref.size)
                    require(temporary.length() == ref.size && MediaObjects.checksum(temporary) == ref.checksum)
                    store.access(session) { db -> db.withTransaction {
                        val dao = db.records()
                        val latest = dao.media(session.scope, direction).firstOrNull { it.id == job.id }
                        if (latest?.version != job.version) return@withTransaction
                        if (dao.pending(session.scope, job.tableName, job.entityId) != null) return@withTransaction
                        val current = dao.snapshot(job.tableName, job.entityId)?.json?.let { Json.parseToJsonElement(it).jsonObject }
                        val currentRef = current?.get("_media")?.jsonObject?.get(job.columnName)
                        if (currentRef == null || currentRef == JsonNull || MediaReference.parse(currentRef) != ref || current["deleted_at"] != JsonNull) {
                            dao.put(job.copy(state = "superseded")); return@withTransaction
                        }
                        MediaObjects(store.host.mediaDirectory).install(temporary, ref)
                        if (job.columnName == "object") {
                            db.openHelper.writableDatabase.execSQL("UPDATE media_assets SET storage_key = ?, inline_data = NULL WHERE id = ?", arrayOf(ref.key, job.entityId))
                            dao.put(EntitySnapshot(job.tableName, job.entityId, requireNotNull(dao.snapshot(job.tableName, job.entityId)).dataVersion,
                                JsonObject(current.toMutableMap().apply { put("storage_key", JsonPrimitive(ref.key)) }).toString()))
                        } else db.openHelper.writableDatabase.execSQL("UPDATE \"${job.tableName}\" SET \"${job.columnName}\" = ? WHERE id = ?", arrayOf(temporary.readBytes(), job.entityId))
                        dao.put(job.copy(state = "complete", lastError = null))
                    } }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                success = false
                store.access(session) { db ->
                    val latest = db.records().media(session.scope, direction).firstOrNull { it.id == job.id }
                    if (latest?.version == job.version) {
                        val attempts = job.attemptCount + 1
                        db.records().put(job.copy(attemptCount = attempts,
                            nextAttemptAt = System.currentTimeMillis() + minOf(3_600_000L, 5_000L * (1L shl attempts.coerceAtMost(10))), lastError = "transfer_failed"))
                    }
                }
            } finally { temporary.delete() }
        }
        return success
    }

    suspend fun ready(session: SyncSession, mutation: PreparedMutation): Boolean = store.access(session) { db ->
        if (mutation.row.operation == "delete") return@access true
        val jobs = db.records().media(session.scope, "upload")
        mutation.request.getValue("snapshot").jsonObject["_media"]?.jsonObject.orEmpty().all { (field, raw) ->
            raw == JsonNull || jobs.any { it.tableName == mutation.row.tableName && it.entityId == mutation.row.entityId &&
                it.columnName == field && it.checksum == MediaReference.parse(raw).checksum && it.state == "complete" }
        }
    }
}
