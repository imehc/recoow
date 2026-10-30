package imehc.recoow.core.domain.history

import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

data class HistoryEntry(val key: String, val route: ToolRoute, val recordId: String, val title: String,
    val timestamp: Long, val detail: String = "", val day: LocalDate? = null, val count: Int = 1)
data class HistoryCursor(val timestamp: Long, val key: String)
data class HistoryQuery(val date: LocalDate? = null, val route: ToolRoute? = null, val search: String = "",
    val zone: ZoneId = ZoneId.systemDefault())
data class HistoryPage(val entries: List<HistoryEntry>, val next: HistoryCursor?)
val historyOrder = compareByDescending<HistoryEntry> { it.timestamp }.thenByDescending { it.key }
fun HistoryEntry.after(cursor: HistoryCursor?) = cursor == null || timestamp < cursor.timestamp || timestamp == cursor.timestamp && key < cursor.key

/** A confirmation captures revisions and children; subsequent edits must not broaden its scope. */
data class HistoryDeletion(val entry: HistoryEntry, val revision: String, val children: Map<String, String> = emptyMap(),
    val childRevision: String? = null, val completion: imehc.recoow.core.domain.reminders.CheckIn? = null)
data class HistoryDeletionPlan(val session: Long, val zone: ZoneId, val targets: List<HistoryDeletion>)
data class HistoryDeletionResult(val deleted: Set<String>, val failed: Set<String>)
interface HistoryRepository {
    val changes: Flow<Unit>
    suspend fun page(query: HistoryQuery, cursor: HistoryCursor? = null, limit: Int = 40): HistoryPage
    suspend fun counts(weekStart: LocalDate, zone: ZoneId): Map<LocalDate, Int>
    suspend fun hasEntries(): Boolean
    suspend fun prepareDeletion(entries: List<HistoryEntry>, zone: ZoneId): HistoryDeletionPlan
    suspend fun delete(plan: HistoryDeletionPlan): HistoryDeletionResult
}
