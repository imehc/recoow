package imehc.recoow.core.domain.media

import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** 生成私有、有上限且已归一化的文件；调用方需保留草稿，直到属主事务成功。 */
data class PreparedMedia(val file: File, val mimeType: String, val width: Int? = null,
    val height: Int? = null, val durationSeconds: Double? = null) : java.io.Serializable

data class MediaAsset(val id: String, val title: String?, val mimeType: String, val byteCount: Long,
    val checksum: String, val width: Int?, val height: Int?, val sortOrder: Long, val references: Int)

/** 记录兜底支持旧版内联图片和待同步引用，且不改写它们。 */
data class MediaLocator(val assetId: String? = null, val table: String? = null, val recordId: String? = null,
    val field: String = "image_data", val draft: File? = null)
enum class MediaAvailability { READY, PENDING, DAMAGED, MISSING }
data class ResolvedMedia(val availability: MediaAvailability, val file: File? = null,
    val title: String? = null, val mimeType: String? = null, val cacheKey: String? = null)

enum class AttachmentOwnerType(val wire: String, val table: String) {
    DIARY("diary", "diary_entries"), FOOD_ENTRY("foodEntry", "food_entries"),
}
data class AttachmentOwner(val type: AttachmentOwnerType, val id: String)
enum class AttachmentKind(val wire: String) { PHOTO("photo"), AUDIO("audio") }
data class MediaAttachment(val id: String = UUID.randomUUID().toString(), val kind: AttachmentKind,
    val title: String? = null, val assetId: String? = null, val mimeType: String,
    val width: Int? = null, val height: Int? = null, val durationSeconds: Double? = null,
    val content: PreparedMedia? = null) : java.io.Serializable {
    val locator get() = MediaLocator(assetId, "media_attachments", id, "data", content?.file)
}

/** 交换照片位置时，音频必须留在原来的槽位。 */
fun List<MediaAttachment>.movePhoto(id: String, delta: Int): List<MediaAttachment> {
    val slots = indices.filter { this[it].kind == AttachmentKind.PHOTO }
    val index = slots.indexOfFirst { this[it].id == id }
    if (index < 0 || index + delta !in slots.indices) return this
    return toMutableList().apply {
        val first = slots[index]; val second = slots[index + delta]
        val value = this[first]; this[first] = this[second]; this[second] = value
    }
}

class AssetInUseException(val references: Int) : IllegalStateException("Asset has $references references")

interface MediaRepository {
    val assets: Flow<List<MediaAsset>>
    fun observeContent(locator: MediaLocator): Flow<ResolvedMedia>
    suspend fun resolve(locator: MediaLocator): ResolvedMedia
    suspend fun createImage(content: PreparedMedia, title: String? = null): String
    suspend fun rename(id: String, title: String?)
    suspend fun replaceImage(id: String, content: PreparedMedia)
    suspend fun reorder(ids: List<String>)
    suspend fun delete(id: String)
    fun attachments(owner: AttachmentOwner): Flow<List<MediaAttachment>>
    /** 在同一事务里保存属主并核对附件；ownerChange 为 null 表示编辑已有属主。 */
    suspend fun saveAttachments(owner: AttachmentOwner, attachments: List<MediaAttachment>, ownerChange: RecordChange? = null)
    suspend fun collectOrphans(): Int
}
