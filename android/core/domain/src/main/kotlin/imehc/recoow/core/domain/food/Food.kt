package imehc.recoow.core.domain.food

import imehc.recoow.core.domain.media.MediaAttachment
import java.io.Serializable
import java.time.*
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class MealKind(val wire: String) {
    BREAKFAST("breakfast"), LUNCH("lunch"), DINNER("dinner"), LATE_NIGHT("lateNightSnack"), SNACK("snack"), DRINK("drink"), OTHER("other");
    companion object { fun at(hour: Int) = when (hour) { in 5..9 -> BREAKFAST; in 10..14 -> LUNCH; in 15..17 -> SNACK; in 18..21 -> DINNER; else -> LATE_NIGHT } }
}
data class FoodEntry(val id: String, val revision: String, val title: String, val meal: MealKind, val occurredAt: Long,
    val portion: String?, val note: String?, val billIds: List<String>, val attachments: List<MediaAttachment>)
data class FoodDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null,
    val title: String = "", val occurredAt: Long = System.currentTimeMillis(),
    val meal: MealKind = MealKind.at(Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()).hour),
    val portion: String = "", val note: String = "", val billIds: List<String> = emptyList(),
    val attachments: List<MediaAttachment> = emptyList()) : Serializable {
    val valid get() = title.isNotBlank()
    companion object {
        fun from(e: FoodEntry) = FoodDraft(e.id, e.revision, e.title, e.occurredAt, e.meal, e.portion.orEmpty(), e.note.orEmpty(), e.billIds, e.attachments)
        fun duplicate(e: FoodEntry, day: LocalDate, zone: ZoneId) = from(e).copy(id = UUID.randomUUID().toString(), revision = null,
            occurredAt = copiedFoodTime(e.occurredAt, day, zone), billIds = emptyList(), attachments = emptyList())
    }
}
fun copiedFoodTime(timestamp: Long, day: LocalDate, zone: ZoneId): Long {
    val time = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime().withNano(0)
    val local = day.atTime(time)
    // Foundation 会把不存在的墙上时间解析为空档之后的第一个有效时刻。
    return (zone.rules.getTransition(local)?.takeIf { it.isGap }?.dateTimeAfter ?: local).atZone(zone).toInstant().toEpochMilli()
}
fun foodDayId(dayStartAt: Long) = "food-day:$dayStartAt"
data class FoodDayRecord(val id: String, val revision: String, val dayStartAt: Long, val title: String)
data class FoodDay(val date: LocalDate, val entries: List<FoodEntry>, val record: FoodDayRecord?) {
    val title get() = record?.title?.trim()?.takeIf { it.isNotEmpty() }
}
data class FoodSuggestion(val title: String, val meal: MealKind, val portion: String?, val uses: Int, val latest: Long)
data class FoodSnapshot(val entries: List<FoodEntry> = emptyList(), val dayRecords: List<FoodDayRecord> = emptyList()) {
    fun days(zone: ZoneId): List<FoodDay> = entries.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        .map { (day, rows) -> FoodDay(day, rows.sortedWith(compareBy<FoodEntry> { it.occurredAt }.thenBy { it.id }),
            dayRecords.firstOrNull { it.dayStartAt == day.atStartOfDay(zone).toInstant().toEpochMilli() }) }.sortedByDescending { it.date }
    fun suggestions(excluding: String? = null): List<FoodSuggestion> = entries.filter { it.id != excluding && it.title.isNotBlank() }
        .groupBy { listOf(it.title.trim().lowercase(Locale.ROOT), it.meal.wire, it.portion.orEmpty().trim().lowercase(Locale.ROOT)) }
        .values.map { group -> val latest = group.maxBy { it.occurredAt }; FoodSuggestion(latest.title.trim(), latest.meal, latest.portion, group.size, latest.occurredAt) }
        .sortedWith(compareByDescending<FoodSuggestion> { it.uses }.thenByDescending { it.latest }.thenBy { it.title }).take(8)
    fun summary(today: LocalDate, zone: ZoneId, firstWeekday: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek): FoodSummary {
        val dayEntries = entries.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        val month = dayEntries.filterKeys { it.year == today.year && it.month == today.month }.values.flatten()
        val todays = dayEntries[today].orEmpty()
        return FoodSummary(entries.size, todays.size, dayEntries.size, todays.map { it.meal }.distinct().size,
            month.count { it.meal == MealKind.SNACK }, month.count { it.meal == MealKind.DRINK }, month.count { it.meal == MealKind.LATE_NIGHT },
            month.count { it.title.contains("奶茶") || it.title.contains("milk tea", ignoreCase = true) }, entries.maxOfOrNull { it.occurredAt })
    }
}
data class FoodSummary(val total: Int, val today: Int, val days: Int, val mealsToday: Int, val snacks: Int, val drinks: Int, val lateNight: Int, val milkTea: Int, val latest: Long?)
interface FoodRepository {
    val snapshot: Flow<FoodSnapshot>
    suspend fun save(draft: FoodDraft): String
    suspend fun delete(id: String, revision: String)
    suspend fun duplicate(entries: Map<String, String>, day: LocalDate, zone: ZoneId)
    suspend fun saveDayTitle(dayStartAt: Long, title: String, revision: String?)
    suspend fun deleteDay(day: LocalDate, zone: ZoneId, entries: Map<String, String>, dayRevision: String?)
}
