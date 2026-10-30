package imehc.recoow.feature.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
    val linked = stringResource(R.string.food_linked_bills); val photos = stringResource(R.string.food_media)
    val groups = state.data.days(ZoneId.systemDefault()); val summary = state.data.summary(LocalDate.now(), ZoneId.systemDefault())
    val filtered = groups.filter { group -> group.title.orEmpty().contains(query.trim(), true) || group.entries.any {
        listOf(it.title, names[it.meal.ordinal], it.portion.orEmpty(), it.note.orEmpty(), if (it.billIds.isNotEmpty()) linked else "", if (it.attachments.isNotEmpty()) photos else "")
            .joinToString(" ").contains(query.trim(), true)
    } }
    RecordPage(stringResource(R.string.food_title), back, tag = "tool_foodJournal", busy = state.busy,
        add = if (!state.loading && !state.failed) stringResource(R.string.food_add) else null, onAdd = { vm.edit() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.food_summary, summary.total, summary.today, summary.days, summary.mealsToday))
                Text(stringResource(R.string.food_week_summary, summary.snacks, summary.drinks, summary.lateNight, summary.milkTea))
                summary.latest?.let { Text(stringResource(R.string.food_latest, recordDateTime(it)), style = MaterialTheme.typography.bodySmall) }
            } } }
            item { RecordField(stringResource(CommonR.string.record_search), query, vm::query, tag = "food_search") }
            if (filtered.isEmpty()) item { Text(stringResource(if (groups.isEmpty()) CommonR.string.record_empty else CommonR.string.record_no_matches)) }
            items(filtered, key = { it.date.toString() }) { group -> Card(Modifier.fillMaxWidth().clickable { vm.showDay(group.date) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.title ?: group.date.toString(), style = MaterialTheme.typography.titleMedium)
                    if (group.title != null) Text(group.date.toString(), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.food_entry_count, group.entries.size))
                    Text(MealKind.entries.filter { meal -> group.entries.any { it.meal == meal } }.joinToString(" · ") { names[it.ordinal] })
                    Text(group.entries.joinToString(" · ") { it.title }, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            } }
        }
    }
}
@Composable private fun FoodDayDetail(vm: FoodViewModel, state: FoodState, group: FoodDay?, date: LocalDate, back: () -> Unit) {
    var deleting by remember { mutableStateOf<FoodDay?>(null) }; var copying by remember { mutableStateOf<List<FoodEntry>?>(null) }
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    RecordPage(group?.title ?: date.toString(), back, tag = "food_day_detail", busy = state.busy,
        action = if (group != null) stringResource(R.string.food_day_title) else null, onAction = { group?.let(vm::editTitle) },
        add = if (!state.loading && !state.failed) stringResource(R.string.food_add) else null, onAdd = { vm.edit() }) {
        if (group == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_empty)) }
        else {
            if (group.title != null) item { Text(group.date.toString()) }
            MealKind.entries.forEach { meal -> val entries = group.entries.filter { it.meal == meal }
                if (entries.isNotEmpty()) {
                    item(key = meal.wire) { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(names[meal.ordinal], Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { copying = entries }, enabled = !state.busy) { Text(stringResource(R.string.food_copy_meal)) }
                    } }
                    items(entries, key = { it.id }) { entry -> OutlinedCard(Modifier.fillMaxWidth().clickable { vm.show(entry.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium); Text(recordDateTime(entry.occurredAt), style = MaterialTheme.typography.bodySmall)
                            entry.portion?.let { Text(it) }; entry.note?.let { Text(it, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                            if (entry.billIds.isNotEmpty() || entry.attachments.isNotEmpty()) Text(stringResource(R.string.food_relations, entry.billIds.size, entry.attachments.size), style = MaterialTheme.typography.bodySmall)
                        }
                    } }
                }
            }
            item { TextButton(onClick = { deleting = group }, enabled = !state.busy) { Text(stringResource(R.string.food_delete_day)) } }
        }
    }
    copying?.let { entries -> CopyFoodDate({ copying = null }) { target -> copying = null; vm.duplicate(entries, target) } }
    deleting?.let { value -> RecordDeleteConfirmation(stringResource(R.string.food_delete_day), stringResource(R.string.food_delete_day_note, value.entries.size), { deleting = null }) { deleting = null; vm.deleteDay(value) } }
}
@Composable private fun FoodEditor(vm: FoodViewModel, state: FoodState, d: FoodDraft, back: () -> Unit) {
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    val suggestions = state.data.suggestions(d.id)
    RecordPage(stringResource(if (d.revision == null) R.string.food_add else R.string.food_edit), back, busy = state.busy,
        action = stringResource(CommonR.string.record_save), actionEnabled = d.valid && !state.loading && !state.failed, onAction = vm::save) {
        item { RecordField(stringResource(R.string.food_name), d.title, { vm.update(d.copy(title = it)) }, !state.busy, tag = "food_title") }
        if (suggestions.isNotEmpty()) item { Text(stringResource(R.string.food_suggestions), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { suggestions.forEach { suggestion ->
                SuggestionChip(onClick = { vm.update(d.copy(title = suggestion.title, meal = suggestion.meal, portion = suggestion.portion.orEmpty())) }, label = { Text(suggestion.title + " · " + names[suggestion.meal.ordinal]) }, enabled = !state.busy)
            } }
        }
        item { RecordChoice(stringResource(R.string.food_meal), d.meal, MealKind.entries.map { it to names[it.ordinal] }, { vm.update(d.copy(meal = it)) }, !state.busy) }
        item { RecordDateTime(stringResource(CommonR.string.record_date), d.occurredAt, { vm.update(d.copy(occurredAt = it)) }, !state.busy) }
        item { RecordField(stringResource(R.string.food_portion), d.portion, { vm.update(d.copy(portion = it)) }, !state.busy) }
        item { RecordField(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, !state.busy, multiline = true) }
        item { Text(stringResource(R.string.food_linked_bills), style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(onClick = vm::pickBills, enabled = !state.busy) { Text(stringResource(R.string.food_select_bills)) } }
        items(d.billIds, key = { "bill:$it" }) { id -> val bill = state.bills.firstOrNull { it.id == id }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(bill?.title ?: stringResource(R.string.food_missing_bill))
                if (bill != null) Text(BigDecimal.valueOf(bill.amounts.final, 2).toPlainString())
                TextButton(onClick = { vm.update(d.copy(billIds = d.billIds - id)) }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_remove)) }
            } }
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
            OutlinedCard(onClick = { vm.update(d.copy(billIds = if (selected) d.billIds - bill.id else d.billIds + bill.id)) }, modifier = Modifier.fillMaxWidth()) {
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
        action = if (entry != null) stringResource(CommonR.string.record_edit) else null, onAction = { entry?.let(vm::edit) }) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item { Text(names[entry.meal.ordinal], style = MaterialTheme.typography.titleMedium); Text(recordDateTime(entry.occurredAt)) }
            entry.portion?.let { item { Text(stringResource(R.string.food_portion), style = MaterialTheme.typography.labelLarge); Text(it) } }
            entry.note?.let { item { Text(stringResource(CommonR.string.record_note), style = MaterialTheme.typography.labelLarge); Text(it) } }
            item { FlowRow {
                OutlinedButton(onClick = { copying = entry }, enabled = !state.busy) { Text(stringResource(R.string.food_copy)) }
                TextButton(onClick = { deleting = entry }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) }
            } }
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
