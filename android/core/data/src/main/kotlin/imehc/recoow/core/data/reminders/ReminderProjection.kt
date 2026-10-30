package imehc.recoow.core.data.reminders

import imehc.recoow.core.database.EntitySnapshot
import imehc.recoow.core.data.journal.*
import imehc.recoow.core.domain.reminders.*
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.serialization.json.*

internal fun EntitySnapshot.reminder(details: List<EntitySnapshot>, inline: Boolean = false): Reminder {
        val f = fields(); val completions = mutableMapOf<LocalDate, CheckIn>()
        f.text("completed_date_keys")?.split(",")?.filter { it.isNotBlank() }?.forEach { key ->
            val day = LocalDate.parse(key); completions[day] = CheckIn(day, f.long("updated_at")!!, CheckInKind.CHECK_IN)
        }
        f.text("completion_records")?.let { Json.parseToJsonElement(it).jsonArray }?.forEach { value ->
            val c = value.jsonObject; val day = LocalDate.parse(c.text("dateKey")); completions[day] = CheckIn(day, c.long("completedAt")!!,
                if (c.text("kindRawValue") == "makeUp") CheckInKind.MAKE_UP else CheckInKind.CHECK_IN, c.text("note"))
        }
        for (detail in details) { val c = detail.fields(); val day = LocalDate.parse(c.text("date_key"))
            if (!c.live) completions.remove(day) else completions[day] = CheckIn(day, c.long("completed_at")!!,
                if (c.text("kind") == "makeUp") CheckInKind.MAKE_UP else CheckInKind.CHECK_IN, c.text("note"))
        }
        val revision = MessageDigest.getInstance("SHA-256").digest((json + details.sortedBy { it.entityId }.joinToString { it.json }).toByteArray()).joinToString("") { "%02x".format(it) }
        return Reminder(entityId, revision, f.text("title")!!, f.long("scheduled_at")!!, f.long("created_at")!!, f.long("updated_at")!!,
            f.text("note"), f.text("memory_icon") ?: "🔔", f.text("image_asset_id"), inline || f.text("image_asset_id") != null || f["_media"]?.jsonObject?.get("image_data")?.let { it != JsonNull } == true,
            ScheduleKind.entries.firstOrNull { it.wire == f.text("schedule_kind") } ?: ScheduleKind.SINGLE, f.long("end_at"), f.long("reminder_time_minutes")!!.toInt(),
            f.text("weekdays")?.split(",")?.mapNotNull { it.toIntOrNull()?.takeIf { it in 1..7 } }?.toSet().orEmpty(), f.long("continuous_days")!!,
            f.long("imported_completed_days") ?: 0, f["is_enabled"]!!.jsonPrimitive.boolean, (f.long("lead_time_minutes") ?: 0).toInt(),
            f.long("completed_at"), f.long("ended_early_at"), f.text("early_end_reason"), f.long("paused_at"), f.text("pause_reason"),
            f.long("total_paused_milliseconds") ?: 0, completions.values.sortedBy { it.date })
    }
