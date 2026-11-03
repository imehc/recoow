package imehc.recoow.feature.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.media.*
import java.math.BigDecimal
import java.time.*

@Composable fun FoodScreen(vm: FoodViewModel, onBack: () -> Unit, recordId: String? = null, onBill: (String) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle(); val day by vm.day.collectAsStateWithLifecycle()
    val titleDraft by vm.titleDraft.collectAsStateWithLifecycle(); val pickingBills by vm.pickingBills.collectAsStateWithLifecycle()
    // A restored destination must not replay its link and discard an in-progress editor.
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) {
            val linkedDay = recordId.takeIf { it.startsWith("day:") }?.removePrefix("day:")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (linkedDay != null) vm.showDay(linkedDay) else vm.show(recordId)
            deliveredRecordId = recordId
        }
    }
    val nested = draft != null || detail != null || day != null || titleDraft != null || pickingBills
    val back: () -> Unit = { if (!state.busy) { if (nested) vm.back() else onBack() } }
    BackHandler(nested || state.busy, back)
    when {
        pickingBills && draft != null -> FoodBillPicker(vm, state, requireNotNull(draft), back)
        titleDraft != null -> {
            val d = requireNotNull(titleDraft)
            RecordPage(stringResource(R.string.food_day_title), back, busy = state.busy, action = stringResource(CommonR.string.record_save), onAction = vm::saveTitle) {
                item { RecordField(stringResource(R.string.food_day_title), d.title, { vm.updateTitle(d.copy(title = it)) }, !state.busy) }
            }
        }
        draft != null -> FoodEditor(vm, state, requireNotNull(draft), back)
        detail != null -> FoodDetail(vm, state, state.data.entries.firstOrNull { it.id == detail }, back, onBill)
        day != null -> FoodDayDetail(vm, state, state.data.days(ZoneId.systemDefault()).firstOrNull { it.date.toString() == day }, LocalDate.parse(requireNotNull(day)), back)
        else -> FoodList(vm, state, back)
    }
    RecordProblem(state.error, vm::clearError)
}
@Composable private fun FoodList(vm: FoodViewModel, state: FoodState, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle(); val names = LocalResources.current.getStringArray(R.array.food_meals)
    var searching by rememberSaveable { mutableStateOf(false) }
    val linked = stringResource(R.string.food_linked_bills); val photos = stringResource(R.string.food_media)
    val groups = state.data.days(ZoneId.systemDefault()); val summary = state.data.summary(LocalDate.now(), ZoneId.systemDefault())
    val filtered = groups.filter { group -> group.title.orEmpty().contains(query.trim(), true) || group.entries.any {
        listOf(it.title, names[it.meal.ordinal], it.portion.orEmpty(), it.note.orEmpty(), if (it.billIds.isNotEmpty()) linked else "", if (it.attachments.isNotEmpty()) photos else "")
            .joinToString(" ").contains(query.trim(), true)
    } }
    RecordPage(stringResource(R.string.food_title), back, tag = "tool_foodJournal", busy = state.busy,
        searching = searching, onSearchToggle = { searching = !searching }, searchTag = "food_search_toggle",
        add = if (!state.loading && !state.failed) stringResource(R.string.food_add) else null, onAdd = { vm.edit() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(CommonR.string.record_overview))
                    RecoowGroup {
                        RecoowMetricGrid(
                            listOf(
                                RecoowMetric(stringResource(R.string.food_metric_total), summary.total.toString()),
                                RecoowMetric(stringResource(R.string.food_metric_today), summary.today.toString()),
                                RecoowMetric(stringResource(R.string.food_metric_days), summary.days.toString()),
                                RecoowMetric(stringResource(R.string.food_metric_meals), summary.mealsToday.toString()),
                            ),
                        )
                        // 本月一栏合并零食/饮品/夜宵/奶茶，不再拆「本周」「本月」两行。
                        RecoowDivider()
                        RecoowRow(
                            title = stringResource(R.string.food_month_title),
                            subtitle = stringResource(R.string.food_month_summary, summary.snacks, summary.drinks, summary.lateNight, summary.milkTea),
                        )
                        summary.latest?.let { latest ->
                            RecoowDivider()
                            RecoowRow(title = stringResource(R.string.food_latest_label), trailingText = recordDateTime(latest))
                        }
                    }
                }
            }
            if (searching) item { RecordSearchField(query, vm::query, tag = "food_search") }
            if (filtered.isEmpty()) item {
                val relaxed = groups.isNotEmpty()
                RecoowGroup {
                    RecoowEmptyState(
                        title = stringResource(if (relaxed) CommonR.string.record_no_matches else CommonR.string.record_empty),
                        actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (relaxed) ({ vm.query("") }) else null,
                    )
                }
            }
            if (filtered.isNotEmpty()) item(key = "days") {
                RecoowGroup {
                    filtered.forEachIndexed { index, group ->
                        val meals = MealKind.entries.filter { meal -> group.entries.any { it.meal == meal } }.joinToString(" · ") { names[it.ordinal] }
                        RecoowRow(
                            title = group.title ?: group.date.toString(),
                            subtitle = listOf(group.date.toString(), stringResource(R.string.food_entry_count, group.entries.size), meals,
                                group.entries.joinToString(" · ") { it.title }).joinToString(" · "),
                            showChevron = true,
                            onClick = { vm.showDay(group.date) },
                        )
                        if (index < filtered.lastIndex) RecoowDivider()
                    }
                }
            }
        }
    }
}
@Composable private fun FoodDayDetail(vm: FoodViewModel, state: FoodState, group: FoodDay?, date: LocalDate, back: () -> Unit) {
    var deleting by remember { mutableStateOf<FoodDay?>(null) }; var copying by remember { mutableStateOf<List<FoodEntry>?>(null) }
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    RecordPage(group?.title ?: date.toString(), back, tag = "food_day_detail", busy = state.busy,
        more = if (group != null) listOf(
            RecoowAction(stringResource(R.string.food_day_title), onClick = { vm.editTitle(group) }),
            RecoowAction(stringResource(R.string.food_delete_day), enabled = !state.busy, onClick = { deleting = group }),
        ) else emptyList(),
        add = if (!state.loading && !state.failed) stringResource(R.string.food_add) else null, onAdd = { vm.edit() }) {
        if (group == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_empty)) }
        else {
            if (group.title != null) item { Text(group.date.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            MealKind.entries.forEach { meal -> val entries = group.entries.filter { it.meal == meal }
                if (entries.isNotEmpty()) {
                    item(key = meal.wire) {
                        Column {
                            // 复制整餐收进分组标题右侧，不再单独占一行。
                            RecoowSectionHeader(names[meal.ordinal], trailing = stringResource(R.string.food_copy_meal), onTrailingClick = { if (!state.busy) copying = entries })
                            RecoowGroup {
                                entries.forEachIndexed { index, entry ->
                                    RecoowRow(
                                        title = entry.title,
                                        subtitle = listOfNotNull(
                                            recordDateTime(entry.occurredAt),
                                            entry.portion,
                                            entry.note,
                                            if (entry.billIds.isNotEmpty() || entry.attachments.isNotEmpty()) stringResource(R.string.food_relations, entry.billIds.size, entry.attachments.size) else null,
                                        ).joinToString(" · "),
                                        showChevron = true,
                                        onClick = { vm.show(entry.id) },
                                    )
                                    if (index < entries.lastIndex) RecoowDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    copying?.let { entries -> CopyFoodDate({ copying = null }) { target -> copying = null; vm.duplicate(entries, target) } }
    deleting?.let { value -> RecordDeleteConfirmation(stringResource(R.string.food_delete_day), stringResource(R.string.food_delete_day_note, value.entries.size), { deleting = null }) { deleting = null; vm.deleteDay(value) } }
}
@Composable private fun FoodEditor(vm: FoodViewModel, state: FoodState, d: FoodDraft, back: () -> Unit) {
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    val suggestions = state.data.suggestions(d.id)
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && !d.valid) stringResource(CommonR.string.record_field_required) else null
    RecordPage(stringResource(if (d.revision == null) R.string.food_add else R.string.food_edit), back, busy = state.busy,
        action = stringResource(CommonR.string.record_save), onAction = { if (d.valid && !state.loading && !state.failed) vm.save() else attempted = true }) {
        item {
            RecordForm {
                RecordFormText(stringResource(R.string.food_name), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, error = titleError, tag = "food_title")
                RecordFormDivider()
                RecordChoice(stringResource(R.string.food_meal), d.meal, MealKind.entries.map { it to names[it.ordinal] },
                    { vm.update(d.copy(meal = it)) }, enabled = !state.busy, bordered = false)
                RecordFormDivider()
                RecordFormDateTime(stringResource(CommonR.string.record_date), d.occurredAt, { vm.update(d.copy(occurredAt = it)) }, !state.busy)
                RecordFormDivider()
                RecordFormText(stringResource(R.string.food_portion), d.portion, { vm.update(d.copy(portion = it)) }, enabled = !state.busy)
                RecordFormDivider()
                RecordFormText(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) },
                    enabled = !state.busy, multiline = true)
            }
        }
        if (suggestions.isNotEmpty()) item {
            RecordForm {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { suggestions.forEach { suggestion ->
                        SuggestionChip(onClick = { vm.update(d.copy(title = suggestion.title, meal = suggestion.meal, portion = suggestion.portion.orEmpty())) }, label = { Text(suggestion.title + " · " + names[suggestion.meal.ordinal]) }, enabled = !state.busy)
                    } }
                }
            }
        }
        item {
            RecordForm {
                RecordFormAction(stringResource(R.string.food_select_bills), onClick = vm::pickBills, enabled = !state.busy)
                if (d.billIds.isNotEmpty()) RecordFormDivider()
                if (d.billIds.isNotEmpty()) Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    d.billIds.forEach { id ->
                        val bill = state.bills.firstOrNull { it.id == id }
                        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(bill?.title ?: stringResource(R.string.food_missing_bill), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            bill?.let {
                                Text(BigDecimal.valueOf(it.amounts.final, 2).toPlainString(), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(CommonR.string.record_remove), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(enabled = !state.busy) { vm.update(d.copy(billIds = d.billIds - id)) }
                                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                        }
                    }
                }
            }
        }
        item { AttachmentInput(d.attachments, { vm.update(d.copy(attachments = it)) }, vm.media, vm.processor, state.preferences) }
    }
}
@Composable private fun FoodBillPicker(vm: FoodViewModel, state: FoodState, d: FoodDraft, back: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = state.bills.filter { (it.title + " " + it.note.orEmpty() + " " + it.category).contains(query.trim(), true) }
    RecordPage(stringResource(R.string.food_select_bills), back, action = stringResource(CommonR.string.record_confirm), onAction = back) {
        item { RecordField(stringResource(CommonR.string.record_search), query, { query = it }) }
        if (filtered.isEmpty()) item { Text(stringResource(CommonR.string.record_no_matches)) }
        items(filtered, key = { it.id }) { bill -> val selected = bill.id in d.billIds
            OutlinedCard(onClick = { vm.update(d.copy(billIds = if (selected) d.billIds - bill.id else d.billIds + bill.id)) }, modifier = Modifier.fillMaxWidth().recordToggleState(selected)) {
                Row(Modifier.padding(12.dp)) { Checkbox(selected, null); Column(Modifier.weight(1f).padding(8.dp)) {
                    Text(bill.title); Text(BigDecimal.valueOf(bill.amounts.final, 2).toPlainString()); Text(recordDateTime(bill.occurredAt), style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
}
@Composable private fun FoodDetail(vm: FoodViewModel, state: FoodState, entry: FoodEntry?, back: () -> Unit, onBill: (String) -> Unit) {
    var deleting by remember { mutableStateOf<FoodEntry?>(null) }; var copying by remember { mutableStateOf<FoodEntry?>(null) }
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    RecordPage(entry?.title ?: stringResource(R.string.food_title), back, tag = "food_detail", busy = state.busy,
        more = if (entry != null) listOf(
            RecoowAction(stringResource(CommonR.string.record_edit), enabled = !state.busy, onClick = { vm.edit(entry) }),
            RecoowAction(stringResource(R.string.food_copy), enabled = !state.busy, onClick = { copying = entry }),
            RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy, onClick = { deleting = entry }),
        ) else emptyList()) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item { Text(names[entry.meal.ordinal], style = MaterialTheme.typography.titleMedium); Text(recordDateTime(entry.occurredAt)) }
            entry.portion?.let { item { Text(stringResource(R.string.food_portion), style = MaterialTheme.typography.labelLarge); Text(it) } }
            entry.note?.let { item { Text(stringResource(CommonR.string.record_note), style = MaterialTheme.typography.labelLarge); Text(it) } }
            if (entry.billIds.isNotEmpty()) item { Text(stringResource(R.string.food_linked_bills), style = MaterialTheme.typography.titleMedium) }
            items(entry.billIds, key = { "bill:$it" }) { id -> val bill = state.bills.firstOrNull { it.id == id }
                OutlinedCard(onClick = { if (bill != null) onBill(id) }, modifier = Modifier.fillMaxWidth(), enabled = bill != null) { Column(Modifier.padding(16.dp)) {
                    Text(bill?.title ?: stringResource(R.string.food_missing_bill)); bill?.let { Text(BigDecimal.valueOf(it.amounts.final, 2).toPlainString()) }
                } }
            }
            item { AttachmentGallery(entry.attachments, vm.media) }
        }
    }
    copying?.let { value -> CopyFoodDate({ copying = null }) { target -> copying = null; vm.copy(value, target) } }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.food_delete_note), { deleting = null }) { deleting = null; vm.delete(value) } }
}
@Composable private fun CopyFoodDate(onDismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    RecordDatePicker(LocalDate.now(), onDismiss, onDate)
}
