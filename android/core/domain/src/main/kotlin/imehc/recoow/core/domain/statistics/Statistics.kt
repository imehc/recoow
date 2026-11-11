package imehc.recoow.core.domain.statistics

import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.reminders.*
import java.time.*
import kotlinx.coroutines.flow.Flow

interface StatisticsRepository { fun dates(route: ToolRoute): Flow<List<Long>> }
data class ToolUsage(val route: ToolRoute, val count: Int, val today: Int, val latest: Long?)
data class UsageDay(val day: LocalDate, val count: Int)
data class UsageOverview(val total: Int = 0, val today: Int = 0, val activeDays: Int = 0, val latest: Long? = null,
    val tools: List<ToolUsage> = emptyList(), val recent: List<UsageDay> = emptyList())

/** 食物按本地日期每天一条；提醒日期只为任务服务，从不代表打卡记录。 */
fun usageOverview(sources: Map<ToolRoute, List<Long>>, now: Long, zone: ZoneId): UsageOverview {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val normalized = ToolRoute.entries.associateWith { route ->
        val timestamps = sources[route].orEmpty()
        if (route == ToolRoute.FOOD) timestamps.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct()
            .map { it.atStartOfDay(zone).toInstant().toEpochMilli() } else timestamps
    }
    val all = normalized.values.flatten()
    val days = all.groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()
    return UsageOverview(all.size, days[today] ?: 0, days.size, all.maxOrNull(), ToolRoute.entries.map { route ->
        val dates = normalized.getValue(route)
        ToolUsage(route, dates.size, dates.count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }, dates.maxOrNull())
    }, (6L downTo 0L).map { offset -> val day = today.minusDays(offset); UsageDay(day, days[day] ?: 0) })
}

data class CheckInProgress(val id: String, val title: String, val completed: Long, val target: Long?,
    val currentStreak: Long, val longestStreak: Long, val finished: Boolean) {
    val fraction get() = target?.let { (completed.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val remaining get() = target?.let { (it - completed).coerceAtLeast(0) }
}
fun checkInProgress(reminders: List<Reminder>, now: Long, zone: ZoneId) = reminders.mapNotNull { r ->
    val target = r.totalDays(zone)
    if (target != null && target <= 1 || r.kind == ScheduleKind.CONTINUOUS && r.completed(zone)) null
    else CheckInProgress(r.id, r.title, target?.let { minOf(it, r.totalCheckIns) } ?: r.totalCheckIns, target,
        r.currentStreak(now, zone), r.longestStreak(zone), r.completed(zone))
}.sortedWith(compareBy<CheckInProgress> { it.finished }.thenByDescending { it.fraction ?: 0f }.thenBy { it.title }.thenBy { it.id })
