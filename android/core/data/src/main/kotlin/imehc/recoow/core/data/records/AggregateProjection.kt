package imehc.recoow.core.data.records

import imehc.recoow.core.database.generated.RecoowDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.*

/** Projections mirror SyncAggregateProjection.swift; they do not create another local mutation. */
internal object AggregateProjection {
    suspend fun refresh(db: RecoowDatabase, changed: Set<String>, zone: ZoneId, scope: String? = null) {
        if (changed.any { it in setOf("bills", "stored_value_accounts", "stored_value_adjustments") }) balances(db, scope)
        if (changed.any { it in setOf("reminders", "reminder_completions") }) completions(db, zone, scope)
    }

    private suspend fun balances(db: RecoowDatabase, scope: String?) {
        val totals = mutableMapOf<String, Long>()
        fun add(id: String, amount: Long) {
            require(id.isNotEmpty())
            totals[id] = Math.addExact(totals[id] ?: 0, amount)
        }
        for (row in db.records().snapshots("stored_value_adjustments")) {
            val fields = Json.parseToJsonElement(row.json).jsonObject
            if (fields["deleted_at"] == JsonNull || fields["deleted_at"] == null)
                add(fields.getValue("account_id").jsonPrimitive.content, fields.getValue("amount_cents").jsonPrimitive.long)
        }
        // Historical bill effects intentionally also participate for tombstones, as on iOS.
        for (row in db.records().snapshots("bills")) {
            val fields = Json.parseToJsonElement(row.json).jsonObject
            val encoded = fields["stored_value_effects"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: continue
            for ((id, delta) in Json.parseToJsonElement(encoded).jsonObject) {
                require(delta is JsonPrimitive && !delta.isString)
                add(id, delta.jsonPrimitive.long)
            }
        }
        for (row in db.records().snapshots("stored_value_accounts")) {
            if (scope != null && db.records().pending(scope, "stored_value_accounts", row.entityId) != null) continue
            val value = totals[row.entityId] ?: 0
            db.openHelper.writableDatabase.execSQL("UPDATE stored_value_accounts SET balance_cents = ? WHERE id = ?", arrayOf<Any?>(value, row.entityId))
            val fields = Json.parseToJsonElement(row.json).jsonObject.toMutableMap()
            fields["balance_cents"] = JsonPrimitive(value)
            db.records().put(row.copy(json = JsonObject(fields).toString()))
        }
    }

    private suspend fun completions(db: RecoowDatabase, zone: ZoneId, scope: String?) {
        val details = db.records().snapshots("reminder_completions").map { Json.parseToJsonElement(it.json).jsonObject }
            .groupBy { it.getValue("reminder_id").jsonPrimitive.content }
        for (reminder in db.records().snapshots("reminders")) {
            if (scope != null && db.records().pending(scope, "reminders", reminder.entityId) != null) continue
            val all = details[reminder.entityId] ?: continue
            val live = all.filter { it["deleted_at"] == null || it["deleted_at"] == JsonNull }
                .sortedBy { it.getValue("date_key").jsonPrimitive.content }
            val fields = Json.parseToJsonElement(reminder.json).jsonObject.toMutableMap()
            val schedule = fields.getValue("schedule_kind").jsonPrimitive.content
            fun number(key: String) = fields[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.long
            val target = when (schedule) {
                "single" -> 1L
                "dailyGoal" -> null
                "dateRange" -> number("end_at")?.let { end ->
                    val start = Instant.ofEpochMilli(requireNotNull(number("scheduled_at"))).atZone(zone).toLocalDate()
                    maxOf(1L, Math.addExact(ChronoUnit.DAYS.between(start, Instant.ofEpochMilli(end).atZone(zone).toLocalDate()), 1))
                }
                else -> maxOf(1L, number("continuous_days") ?: 1)
            }
            val complete = target != null && Math.addExact(live.size.toLong(), number("imported_completed_days") ?: 0) >= target
            val ended = schedule == "continuousDays" && number("ended_early_at") != null
            val completedAt = if (complete && !ended) number("completed_at") ?: live.maxOfOrNull { it.getValue("completed_at").jsonPrimitive.long } else null
            val records = JsonArray(live.map { row -> buildJsonObject {
                put("dateKey", row.getValue("date_key")); put("completedAt", row.getValue("completed_at"))
                put("kindRawValue", row.getValue("kind"))
                row["note"]?.takeIf { it != JsonNull }?.let { put("note", it) }
            } }).toString()
            val keys = live.joinToString(",") { it.getValue("date_key").jsonPrimitive.content }
            fields["completed_date_keys"] = JsonPrimitive(keys)
            fields["completion_records"] = JsonPrimitive(records)
            fields["completed_at"] = completedAt?.let(::JsonPrimitive) ?: JsonNull
            db.openHelper.writableDatabase.execSQL("UPDATE reminders SET completed_date_keys = ?, completion_records = ?, completed_at = ? WHERE id = ?",
                arrayOf<Any?>(keys, records, completedAt, reminder.entityId))
            db.records().put(reminder.copy(json = JsonObject(fields).toString()))
            RoomRecordRepository.queueNotification(db, "reminders", reminder.entityId, fields.getValue("updated_at").jsonPrimitive.long)
        }
    }
}
