package imehc.recoow.core.domain.decision

import imehc.recoow.core.domain.media.*
import java.io.Serializable
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class DecisionCollection(val id: String, val revision: String, val title: String, val note: String?)
data class CollectionDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null, val title: String = "", val note: String = "") : Serializable {
    companion object { fun from(c: DecisionCollection) = CollectionDraft(c.id, c.revision, c.title, c.note.orEmpty()) }
}
data class DecisionOption(val id: String, val revision: String, val collectionId: String, val title: String, val detail: String?,
    val customInfo: String?, val weight: Long, val enabled: Boolean, val imageAssetId: String?, val hasImage: Boolean) {
    val image get() = MediaLocator(imageAssetId, "decision_options", id)
}
data class OptionDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null, val collectionId: String,
    val title: String = "", val detail: String = "", val customInfo: String = "", val weight: String = "1", val enabled: Boolean = true,
    val imageAssetId: String? = null, val hasImage: Boolean = false, val imageChanged: Boolean = false, val imageContent: PreparedMedia? = null) : Serializable {
    // 导入的权重若超出表单范围，会原样保留，直到被显式修改。
    val valid get() = title.isNotBlank() && (weight.toLongOrNull()?.let { it >= 1 } == true)
    val image get() = if (hasImage) MediaLocator(imageAssetId, "decision_options", id, draft = imageContent?.file) else null
    companion object { fun from(o: DecisionOption) = OptionDraft(o.id, o.revision, o.collectionId, o.title, o.detail.orEmpty(), o.customInfo.orEmpty(), o.weight.toString(), o.enabled, o.imageAssetId, o.hasImage) }
}
data class DecisionChoice(val id: String, val revision: String, val collectionId: String, val collectionTitle: String,
    val optionId: String, val title: String, val detail: String?, val customInfo: String?, val imageAssetId: String?, val hasImage: Boolean, val selectedAt: Long) {
    val image get() = MediaLocator(imageAssetId, "decision_choice_records", id, "option_image_data")
}
data class DecisionSnapshot(val collections: List<DecisionCollection> = emptyList(), val options: List<DecisionOption> = emptyList(), val history: List<DecisionChoice> = emptyList())
/** 随机结果只抽一次，动画不会改变已保存的结果；用 BigInteger 避免导入权重溢出。 */
fun weightedChoice(options: List<DecisionOption>, ticket: (java.math.BigInteger) -> java.math.BigInteger): DecisionOption {
    val candidates = options.filter { it.enabled }; require(candidates.isNotEmpty())
    val weights = candidates.map { java.math.BigInteger.valueOf(it.weight.coerceAtLeast(1)) }
    val total = weights.fold(java.math.BigInteger.ZERO, java.math.BigInteger::add)
    var remaining = ticket(total); require(remaining.signum() >= 0 && remaining < total)
    for (index in candidates.indices) { if (remaining < weights[index]) return candidates[index]; remaining -= weights[index] }
    error("Invalid random ticket")
}
interface DecisionsRepository {
    val snapshot: Flow<DecisionSnapshot>
    suspend fun saveCollection(draft: CollectionDraft): String
    suspend fun deleteCollection(id: String, revision: String)
    suspend fun saveOption(draft: OptionDraft): String
    suspend fun deleteOption(id: String, revision: String)
    suspend fun choose(collectionId: String): String
    suspend fun deleteChoice(id: String, revision: String)
}
