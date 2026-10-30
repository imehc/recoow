package imehc.recoow.core.data.history

import androidx.sqlite.db.SimpleSQLiteQuery
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.data.journal.*
import imehc.recoow.core.data.reminders.reminder
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.*

internal data class HistorySource(val route: ToolRoute, val table: String, val time: String, val title: String,
    val detail: String, val search: List<String>)
internal val historySources = listOf(
    HistorySource(ToolRoute.LOCATION, "tracks", "started_at", "name", "note", listOf("name", "note")),
    HistorySource(ToolRoute.DECISION, "decision_choice_records", "selected_at", "option_title", "collection_title", listOf("collection_title", "option_title", "option_detail", "option_custom_info")),
    HistorySource(ToolRoute.ITEMS, "stored_items", "updated_at", "title", "location", listOf("title", "location", "note", "tags", "search_keywords")),
    HistorySource(ToolRoute.BILLS, "bills", "occurred_at", "title", "note", listOf("title", "note", "transaction_type", "category", "payment_method", "start_location", "end_location", "transport_lines")),
    HistorySource(ToolRoute.DIARY, "diary_entries", "occurred_at", "title", "content", listOf("title", "content", "mood", "tags_json")),
    HistorySource(ToolRoute.ANNIVERSARIES, "anniversaries", "occurred_at", "title", "note", listOf("title", "note", "category")),
)

internal fun RecoowDatabase.sourcePage(source: HistorySource, query: HistoryQuery, cursor: HistoryCursor?, limit: Int): List<HistoryEntry> {
    val clauses = mutableListOf("deleted_at IS NULL")
    val args = mutableListOf<Any?>()
    query.date?.let { day -> clauses += "${source.time} >= ? AND ${source.time} < ?"
        args += day.atStartOfDay(query.zone).toInstant().toEpochMilli(); args += day.plusDays(1).atStartOfDay(query.zone).toInstant().toEpochMilli() }
    cursor?.let { clauses += "(${source.time} < ? OR (${source.time} = ? AND (? || id) < ?))"
        args.addAll(listOf(it.timestamp, it.timestamp, "${source.route.id}:", it.key)) }
    val search = query.search.trim()
    if (search.isNotEmpty()) {
        // Treat user percent/underscore literally, and bind every value rather than interpolating it.
        clauses += source.search.joinToString(" OR ", "(", ")") { "COALESCE($it, '') LIKE ? ESCAPE '\\'" }
        val pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        repeat(source.search.size) { args += pattern }
    }
    args += limit
    val sql = "SELECT id, ${source.time}, ${source.title}, substr(COALESCE(${source.detail}, ''), 1, 180) FROM ${source.table} WHERE ${clauses.joinToString(" AND ")} ORDER BY ${source.time} DESC, id DESC LIMIT ?"
    return openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c -> buildList {
        while (c.moveToNext()) add(HistoryEntry("${source.route.id}:${c.getString(0)}", source.route, c.getString(0), c.getString(2), c.getLong(1), c.getString(3)))
    } }
}

internal suspend fun RecoowDatabase.reminderHistory(): List<HistoryEntry> {
    val details = records().snapshots("reminder_completions").groupBy { it.fields().text("reminder_id") }
    return records().snapshots("reminders").filter { it.fields().live }.flatMap { row ->
        val r = row.reminder(details[row.entityId].orEmpty())
        r.completions.map { c -> HistoryEntry("${ToolRoute.REMINDERS.id}:${r.id}:${c.date}", ToolRoute.REMINDERS, r.id,
            r.title, c.completedAt, listOfNotNull(r.note, c.note, r.icon, r.kind.wire).joinToString(" · "), c.date) }
    }
}

/** Only text and timestamps are projected: no image, attachment, or full entity payload is read. */
internal fun RecoowDatabase.foodHistory(zone: ZoneId): List<HistoryEntry> {
    data class Day(var updated: Long, var count: Int = 0, val search: StringBuilder = StringBuilder())
    val days = linkedMapOf<LocalDate, Day>()
    openHelper.readableDatabase.query("SELECT occurred_at, updated_at, title, meal_kind, portion, note FROM food_entries WHERE deleted_at IS NULL").use { c ->
        while (c.moveToNext()) {
            val date = Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate()
            val day = days.getOrPut(date) { Day(c.getLong(1)) }; day.updated = maxOf(day.updated, c.getLong(1)); day.count++
            for (i in 2..5) if (!c.isNull(i)) day.search.append(c.getString(i)).append('\n')
        }
    }
    val titles = mutableMapOf<LocalDate, String>()
    openHelper.readableDatabase.query("SELECT day_start_at, title, updated_at FROM food_day_records WHERE deleted_at IS NULL").use { c ->
        while (c.moveToNext()) {
            val date = Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate()
            // Match the feature repository's exact local day boundary, including DST and zone changes.
            if (date.atStartOfDay(zone).toInstant().toEpochMilli() == c.getLong(0)) {
                titles[date] = c.getString(1); days[date]?.let { it.updated = maxOf(it.updated, c.getLong(2)) }
            }
        }
    }
    return days.map { (date, day) -> HistoryEntry("${ToolRoute.FOOD.id}:$date", ToolRoute.FOOD, "day:$date",
        titles[date]?.takeIf { it.isNotBlank() } ?: date.toString(), day.updated, day.search.toString(), date, day.count) }
}

internal fun HistoryEntry.matches(query: HistoryQuery) = (query.date == null ||
    (if (route == ToolRoute.FOOD) day else Instant.ofEpochMilli(timestamp).atZone(query.zone).toLocalDate()) == query.date) &&
    (query.search.isBlank() || title.contains(query.search.trim(), true) || detail.contains(query.search.trim(), true))
