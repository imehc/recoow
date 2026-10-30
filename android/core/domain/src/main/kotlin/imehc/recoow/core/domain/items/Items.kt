package imehc.recoow.core.domain.items

import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.NamedRecord
import imehc.recoow.core.domain.records.NamedRecordDraft
import java.io.Serializable
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class StoredItem(val id: String, val revision: String, val title: String, val location: String, val categoryId: String?,
    val note: String?, val tags: String?, val keywords: String?, val imageAssetId: String?, val hasImage: Boolean, val updatedAt: Long) {
    val image get() = MediaLocator(imageAssetId, "stored_items", id)
}
data class ItemDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null,
    val title: String = "", val location: String = "", val categoryId: String? = null, val note: String = "",
    val tags: String = "", val keywords: String = "", val imageAssetId: String? = null,
    val hasImage: Boolean = false, val imageChanged: Boolean = false, val imageContent: PreparedMedia? = null) : Serializable {
    val valid get() = title.isNotBlank() && location.isNotBlank()
    val image get() = if (hasImage) MediaLocator(imageAssetId, "stored_items", id, draft = imageContent?.file) else null
    companion object { fun from(i: StoredItem) = ItemDraft(i.id, i.revision, i.title, i.location, i.categoryId, i.note.orEmpty(), i.tags.orEmpty(), i.keywords.orEmpty(), i.imageAssetId, i.hasImage) }
}
data class ItemsSnapshot(val items: List<StoredItem> = emptyList(), val categories: List<NamedRecord> = emptyList())
interface ItemsRepository {
    val snapshot: Flow<ItemsSnapshot>
    suspend fun save(draft: ItemDraft): String
    suspend fun delete(id: String, revision: String)
    suspend fun saveCategory(draft: NamedRecordDraft): String
    suspend fun deleteCategory(id: String, revision: String)
}
