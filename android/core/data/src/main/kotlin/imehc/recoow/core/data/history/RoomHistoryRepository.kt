package imehc.recoow.core.data.history

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.anniversaries.AnniversariesRepository
import imehc.recoow.core.domain.bills.BillsRepository
import imehc.recoow.core.domain.decision.DecisionsRepository
import imehc.recoow.core.domain.diary.DiaryRepository
import imehc.recoow.core.domain.food.FoodRepository
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.items.ItemsRepository
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.records.StaleRecordException
import imehc.recoow.core.domain.reminders.RemindersRepository
import imehc.recoow.core.domain.tracks.TracksRepository
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RoomHistoryRepository(private val host: DatabaseHost, private val bills: BillsRepository,
    private val reminders: RemindersRepository, private val food: FoodRepository, private val diary: DiaryRepository,
    private val items: ItemsRepository, private val decisions: DecisionsRepository,
    private val anniversaries: AnniversariesRepository, private val tracks: TracksRepository) : HistoryRepository {
    override val changes = host.observe { it.invalidationTracker.createFlow("entity_snapshots").map { Unit } }.conflate()

    override suspend fun page(query: HistoryQuery, cursor: HistoryCursor?, limit: Int): HistoryPage = host.access { db -> db.withTransaction {
        require(limit in 1..200)
        val entries = historySources.filter { query.route == null || query.route == it.route }
            .flatMap { db.sourcePage(it, query, cursor, limit + 1) }.toMutableList()
        if (query.route == null || query.route == ToolRoute.REMINDERS) entries += db.reminderHistory()
            .asSequence().filter { it.matches(query) && it.after(cursor) }.sortedWith(historyOrder).take(limit + 1).toList()
        if (query.route == null || query.route == ToolRoute.FOOD) entries += db.foodHistory(query.zone)
            .asSequence().filter { it.matches(query) && it.after(cursor) }.sortedWith(historyOrder).take(limit + 1)
            .map { it.copy(detail = it.detail.lineSequence().filter(String::isNotBlank).take(3).joinToString(" · ").take(180)) }.toList()
        val sorted = entries.sortedWith(historyOrder)
        val page = sorted.take(limit)
        HistoryPage(page, if (sorted.size > limit) page.last().let { HistoryCursor(it.timestamp, it.key) } else null)
    } }

    override suspend fun counts(weekStart: LocalDate, zone: ZoneId): Map<LocalDate, Int> = host.access { db -> db.withTransaction {
        val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val times = historySources.flatMap { s -> db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            "SELECT ${s.time} FROM ${s.table} WHERE deleted_at IS NULL AND ${s.time} >= ? AND ${s.time} < ?", arrayOf(start, end))).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            } }.toMutableList()
        times += db.reminderHistory().map { it.timestamp }.filter { it >= start && it < end }
        // Food badges count meal dates even when a day's title was edited later.
        times += db.foodHistory(zone).mapNotNull { it.day }.filter { it >= weekStart && it < weekStart.plusDays(7) }
            .map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
        times.groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()
    } }

    override suspend fun hasEntries(): Boolean = host.access { db ->
        (historySources.map { it.table } + "food_entries").any { table ->
            db.openHelper.readableDatabase.query("SELECT 1 FROM $table WHERE deleted_at IS NULL LIMIT 1").use { it.moveToFirst() }
        } || db.reminderHistory().isNotEmpty()
    }

    override suspend fun prepareDeletion(entries: List<HistoryEntry>, zone: ZoneId): HistoryDeletionPlan {
        require(entries.isNotEmpty())
        val token = host.sessionToken()
        val routes = entries.map { it.route }.toSet()
        val billRows = if (ToolRoute.BILLS in routes) bills.bills.first().filterNot { it.deleted }.associateBy { it.id } else emptyMap()
        val reminderRows = if (ToolRoute.REMINDERS in routes) reminders.reminders.first().associateBy { it.id } else emptyMap()
        val foodRows = if (ToolRoute.FOOD in routes) food.snapshot.first().days(zone).associateBy { it.date } else emptyMap()
        val diaryRows = if (ToolRoute.DIARY in routes) diary.snapshot.first().entries.associateBy { it.id } else emptyMap()
        val itemRows = if (ToolRoute.ITEMS in routes) items.snapshot.first().items.associateBy { it.id } else emptyMap()
        val choiceRows = if (ToolRoute.DECISION in routes) decisions.snapshot.first().history.associateBy { it.id } else emptyMap()
        val anniversaryRows = if (ToolRoute.ANNIVERSARIES in routes) anniversaries.records.first().associateBy { it.id } else emptyMap()
        val targets = entries.distinctBy { it.key }.map { e ->
            when (e.route) {
                ToolRoute.BILLS -> billRows[e.recordId]?.let { HistoryDeletion(e.copy(title = it.title), it.revision) }
                ToolRoute.REMINDERS -> reminderRows[e.recordId]?.let { r -> r.completions.firstOrNull { it.date == e.day }?.let {
                    HistoryDeletion(e.copy(title = r.title), r.revision, completion = it)
                } }
                ToolRoute.FOOD -> foodRows[e.day]?.let { group -> HistoryDeletion(e.copy(title = group.title ?: group.date.toString(), count = group.entries.size), "",
                    group.entries.associate { it.id to it.revision }, group.record?.revision) }
                ToolRoute.DIARY -> diaryRows[e.recordId]?.let { HistoryDeletion(e.copy(title = it.title), it.revision) }
                ToolRoute.ITEMS -> itemRows[e.recordId]?.let { HistoryDeletion(e.copy(title = it.title), it.revision) }
                ToolRoute.DECISION -> choiceRows[e.recordId]?.let { HistoryDeletion(e.copy(title = it.title), it.revision) }
                ToolRoute.ANNIVERSARIES -> anniversaryRows[e.recordId]?.let { HistoryDeletion(e.copy(title = it.title), it.revision) }
                ToolRoute.LOCATION -> tracks.detail(e.recordId).first().let { d -> d.track?.let { HistoryDeletion(e.copy(title = it.name), d.revision) } }
            } ?: throw StaleRecordException()
        }
        if (host.sessionToken() != token) throw StaleRecordException()
        return HistoryDeletionPlan(token, zone, targets)
    }

    override suspend fun delete(plan: HistoryDeletionPlan): HistoryDeletionResult {
        val deleted = mutableSetOf<String>(); val failed = mutableSetOf<String>()
        // Each business operation is atomic. A partial batch is reported explicitly and can be retried.
        for (target in plan.targets) {
            val e = target.entry
            try {
                if (host.sessionToken() != plan.session) throw StaleRecordException()
                when (e.route) {
                    ToolRoute.BILLS -> bills.delete(e.recordId, target.revision)
                    ToolRoute.REMINDERS -> reminders.deleteCompletion(e.recordId, requireNotNull(e.day), requireNotNull(target.completion))
                    ToolRoute.FOOD -> food.deleteDay(requireNotNull(e.day), plan.zone, target.children, target.childRevision)
                    ToolRoute.DIARY -> diary.delete(e.recordId, target.revision)
                    ToolRoute.ITEMS -> items.delete(e.recordId, target.revision)
                    ToolRoute.DECISION -> decisions.deleteChoice(e.recordId, target.revision)
                    ToolRoute.ANNIVERSARIES -> anniversaries.delete(e.recordId, target.revision)
                    ToolRoute.LOCATION -> tracks.delete(e.recordId, target.revision)
                }
                deleted += e.key
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed += e.key }
        }
        return HistoryDeletionResult(deleted, failed)
    }
}
