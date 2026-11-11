package imehc.recoow.core.domain.diary

import imehc.recoow.core.domain.media.MediaAttachment
import imehc.recoow.core.domain.records.NamedRecord
import imehc.recoow.core.domain.records.NamedRecordDraft
import java.io.Serializable
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class DiaryMood(val wire: String) { NEUTRAL("neutral"), HAPPY("happy"), CALM("calm"), TIRED("tired"), STRESSED("stressed"), SAD("sad") }
data class DiaryTagReference(val key: String, val value: String) : Serializable
enum class DiarySource(val wire: String, val table: String, val icon: String) {
    TRACK("track", "tracks", "location.fill"), BILL("bill", "bills", "receipt.fill"),
    REMINDER("reminder", "reminders", "checkmark.circle.fill"), ANNIVERSARY("anniversary", "anniversaries", "calendar"),
    ITEM("storedItem", "stored_items", "shippingbox.fill"), CHOICE("decisionChoice", "decision_choice_records", "shuffle")
}
/** 可读快照在来源被删除后仍然保留；选中某个链接不会改变它的来源。 */
data class DiaryLink(val sourceType: String, val sourceId: String, val title: String, val subtitle: String? = null,
    val icon: String, val occurredAt: Long? = null, val snapshot: String? = null) : Serializable {
    val key get() = "$sourceType:$sourceId"
}
data class DiaryEntry(val id: String, val revision: String, val title: String, val content: String,
    val mood: DiaryMood, val occurredAt: Long, val tags: List<DiaryTagReference>, val links: List<DiaryLink>,
    val attachments: List<MediaAttachment>, val latitude: Double? = null, val longitude: Double? = null,
    val accuracy: Double? = null)
data class DiaryDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null,
    val title: String = "", val content: String = "", val mood: DiaryMood = DiaryMood.NEUTRAL,
    val occurredAt: Long = System.currentTimeMillis(), val tags: List<DiaryTagReference> = emptyList(),
    val links: List<DiaryLink> = emptyList(), val attachments: List<MediaAttachment> = emptyList(),
    val latitude: Double? = null, val longitude: Double? = null, val accuracy: Double? = null) : Serializable {
    val valid get() = title.isNotBlank() || content.isNotBlank()
    companion object { fun from(e: DiaryEntry) = DiaryDraft(e.id, e.revision, e.title, e.content, e.mood, e.occurredAt,
        e.tags, e.links, e.attachments, e.latitude, e.longitude, e.accuracy) }
}
data class DiarySnapshot(val entries: List<DiaryEntry> = emptyList(), val tags: List<NamedRecord> = emptyList()) {
    fun tagReferences(): List<DiaryTagReference> = (entries.flatMap { it.tags } + tags.map { DiaryTagReference(it.id, it.name) })
        .associateBy { it.key }.values.sortedBy { it.value.lowercase() }
    fun resolvedTags(entry: DiaryEntry) = entry.tags.map { ref -> tags.firstOrNull { it.id == ref.key }?.let { ref.copy(value = it.name) } ?: ref }
    fun summary(today: LocalDate, zone: ZoneId): DiarySummary {
        val dates = entries.map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        val days = dates.toSet(); var cursor = if (today in days) today else today.minusDays(1); var streak = 0
        while (cursor in days) { streak++; cursor = cursor.minusDays(1) }
        return DiarySummary(entries.size, dates.count { it == today }, days.size, streak)
    }
}
data class DiarySummary(val total: Int, val today: Int, val activeDays: Int, val streak: Int)
interface DiaryRepository {
    val snapshot: Flow<DiarySnapshot>
    fun sources(source: DiarySource): Flow<List<DiaryLink>>
    suspend fun save(draft: DiaryDraft, defaultTitle: String): String
    suspend fun delete(id: String, revision: String)
    suspend fun saveTag(draft: NamedRecordDraft): String
    suspend fun deleteTag(id: String, revision: String)
}
