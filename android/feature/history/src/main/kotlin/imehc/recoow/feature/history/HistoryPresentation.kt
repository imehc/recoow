package imehc.recoow.feature.history

import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.decision.DecisionsRepository
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.food.FoodRepository
import imehc.recoow.core.domain.items.ItemsRepository
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.reminders.RemindersRepository
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

internal data class HistoryAppearance(val image: MediaLocator? = null, val bill: BillRecord? = null,
    val subtitle: String? = null, val mood: DiaryMood? = null, val tags: List<String> = emptyList(), val icon: String? = null)

/** 仅用于展示的只读元数据；历史分页、排序和删除仍使用原来的条目。 */
class HistoryPresentation @Inject constructor(private val bills: BillsRepository, private val diary: DiaryRepository,
    private val food: FoodRepository, private val items: ItemsRepository, private val decisions: DecisionsRepository,
    private val reminders: RemindersRepository) {
    internal fun bind(scope: CoroutineScope, zone: Flow<ZoneId>): Map<ToolRoute, StateFlow<Map<String, HistoryAppearance>>> {
        val sources: Map<ToolRoute, Flow<Map<String, HistoryAppearance>>> = mapOf(
            ToolRoute.BILLS to bills.bills.map { rows -> rows.filterNot { it.deleted }.associate { it.id to HistoryAppearance(
                if (it.hasImage) MediaLocator(it.imageAssetId, "bills", it.id) else null, bill = it) } },
            ToolRoute.DIARY to diary.snapshot.map { snapshot -> snapshot.entries.associate { row -> row.id to HistoryAppearance(
                row.attachments.firstOrNull { it.kind == AttachmentKind.PHOTO }?.locator, subtitle = row.content,
                mood = row.mood, tags = snapshot.resolvedTags(row).map { it.value }) } },
            ToolRoute.FOOD to combine(food.snapshot, zone) { snapshot, currentZone -> snapshot.days(currentZone).associate { day ->
                "day:${day.date}" to HistoryAppearance(day.entries.flatMap { it.attachments }.firstOrNull { it.kind == AttachmentKind.PHOTO }?.locator)
            } },
            ToolRoute.ITEMS to items.snapshot.map { snapshot -> snapshot.items.associate { row -> row.id to HistoryAppearance(
                if (row.hasImage) row.image else null, subtitle = row.location,
                tags = snapshot.categories.firstOrNull { it.id == row.categoryId }?.let { listOf(it.name) }.orEmpty()) } },
            ToolRoute.DECISION to decisions.snapshot.map { snapshot -> snapshot.history.associate { row -> row.id to HistoryAppearance(
                if (row.hasImage) row.image else null, subtitle = row.collectionTitle) } },
            ToolRoute.REMINDERS to reminders.reminders.map { rows -> rows.associate { row -> row.id to HistoryAppearance(
                if (row.hasImage) MediaLocator(row.imageAssetId, "reminders", row.id) else null, icon = row.icon) } },
        )
        return sources.mapValues { (_, source) -> source.catch { emit(emptyMap()) }.flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap()) }
    }
}
