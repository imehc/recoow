package imehc.recoow.core.data.journal

import imehc.recoow.core.database.EntitySnapshot
import imehc.recoow.core.domain.diary.*
import java.math.BigDecimal
import kotlinx.serialization.json.*

internal fun diarySource(source: DiarySource, row: EntitySnapshot, categories: Map<String, String>): DiaryLink {
    val f = row.fields()
    val timestampKey = when (source) { DiarySource.TRACK -> "started_at"; DiarySource.REMINDER -> "scheduled_at"; DiarySource.ITEM -> "updated_at"; DiarySource.CHOICE -> "selected_at"; else -> "occurred_at" }
    val title = f.text(when (source) { DiarySource.TRACK -> "name"; DiarySource.CHOICE -> "option_title"; else -> "title" }).orEmpty()
    val amount = f.long("final_amount_cents")?.let { BigDecimal.valueOf(it, 2).toPlainString() }
    val subtitle = when (source) {
        DiarySource.TRACK -> listOfNotNull(f.double("distance_m")?.let { "$it m" }, f.long("duration_s")?.let { "$it s" })
        DiarySource.BILL -> listOfNotNull(amount, f.text("category"), f.text("payment_method"))
        DiarySource.REMINDER -> listOfNotNull(f.text("note"))
        DiarySource.ANNIVERSARY -> listOfNotNull(f.text("category"), f.text("note"))
        DiarySource.ITEM -> listOfNotNull(f.text("location"), categories[f.text("category_id")])
        DiarySource.CHOICE -> listOfNotNull(f.text("collection_title"), f.text("option_detail"))
    }.filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }
    // 快照字段名跟随 iOS 的 Codable 快照，与数据库里的 snake_case 命名无关。
    val names = when (source) {
        DiarySource.TRACK -> mapOf("name" to "name", "startedAt" to "started_at", "endedAt" to "ended_at", "distanceMeters" to "distance_m", "durationSeconds" to "duration_s", "note" to "note")
        DiarySource.BILL -> mapOf("title" to "title", "finalAmountCents" to "final_amount_cents", "transactionType" to "transaction_type", "category" to "category", "paymentMethod" to "payment_method", "startLocation" to "start_location", "endLocation" to "end_location", "occurredAt" to "occurred_at", "note" to "note")
        DiarySource.REMINDER -> mapOf("title" to "title", "note" to "note", "scheduleKind" to "schedule_kind", "scheduledAt" to "scheduled_at")
        DiarySource.ANNIVERSARY -> mapOf("title" to "title", "note" to "note", "category" to "category", "occurredAt" to "occurred_at", "dateCalendar" to "date_calendar", "isYearly" to "is_yearly")
        DiarySource.ITEM -> mapOf("title" to "title", "location" to "location", "note" to "note", "tags" to "tags", "updatedAt" to "updated_at")
        DiarySource.CHOICE -> mapOf("collectionID" to "collection_id", "collectionTitle" to "collection_title", "optionID" to "option_id", "optionTitle" to "option_title", "optionDetail" to "option_detail", "optionCustomInfo" to "option_custom_info", "selectedAt" to "selected_at")
    }
    val snapshot = buildJsonObject {
        put("id", row.entityId)
        names.forEach { (key, field) -> f[field]?.takeIf { it != JsonNull }?.let { put(key, it) } }
        if (source == DiarySource.BILL) put("displayAmount", amount.orEmpty())
        if (source == DiarySource.ITEM) categories[f.text("category_id")]?.let { put("categoryName", it) }
    }
    return DiaryLink(source.wire, row.entityId, title, subtitle, source.icon, f.long(timestampKey), snapshot.toString())
}
