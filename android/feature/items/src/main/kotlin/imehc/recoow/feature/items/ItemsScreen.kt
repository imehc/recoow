package imehc.recoow.feature.items

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.items.*
import imehc.recoow.core.media.*

@Composable fun ItemsScreen(vm: ItemsViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle(); val managing by vm.managing.collectAsStateWithLifecycle()
    val categoryDraft by vm.categoryDraft.collectAsStateWithLifecycle()
    // A restored destination must not replay its link and discard an in-progress editor.
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) { vm.show(recordId); deliveredRecordId = recordId }
    }
    val nested = draft != null || detail != null || managing
    val back: () -> Unit = { if (!state.busy) { if (nested) vm.back() else onBack() } }
    BackHandler(nested || state.busy, back)
    when {
        managing -> NamedRecordManager(stringResource(R.string.items_categories), state.data.categories, categoryDraft, state.busy,
            stringResource(R.string.items_delete_category_note), back, vm::editCategory, vm::editCategory, vm::saveCategory, vm::deleteCategory)
        draft != null -> ItemEditor(vm, state, requireNotNull(draft), back)
        detail != null -> ItemDetail(vm, state, state.data.items.firstOrNull { it.id == detail }, back)
        else -> ItemsList(vm, state, back)
    }
    RecordProblem(state.error, vm::clearError)
}
@Composable private fun ItemsList(vm: ItemsViewModel, state: ItemsState, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle(); val category by vm.categoryFilter.collectAsStateWithLifecycle()
    val uncategorized = stringResource(R.string.items_uncategorized)
    val names = state.data.categories.associate { it.id to it.name }
    val filtered = state.data.items.filter { item ->
        (category == null || item.categoryId == category) && listOf(item.title, item.location, item.note.orEmpty(), item.tags.orEmpty(), item.keywords.orEmpty(), names[item.categoryId] ?: uncategorized)
            .joinToString(" ").contains(query.trim(), true)
    }
    RecordPage(stringResource(R.string.items_title), back, tag = "tool_itemLocator", busy = state.busy,
        action = stringResource(R.string.items_categories), actionEnabled = !state.loading && !state.failed, onAction = vm::manageCategories,
        add = if (!state.failed && !state.loading) stringResource(R.string.items_add) else null, onAdd = { vm.edit() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item { RecordField(stringResource(CommonR.string.record_search), query, vm::query, tag = "items_search") }
            item { RecordChoice(stringResource(R.string.items_category), category,
                listOf(null to stringResource(CommonR.string.record_all)) + state.data.categories.map { it.id to it.name }, vm::filterCategory) }
            if (filtered.isEmpty()) item { Text(stringResource(if (state.data.items.isEmpty()) CommonR.string.record_empty else CommonR.string.record_no_matches)) }
            items(filtered, key = { it.id }) { item -> Card(Modifier.fillMaxWidth().clickable { vm.show(item.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.hasImage) MediaImage(item.image, vm.media, Modifier.fillMaxWidth().height(160.dp))
                    Text(item.title, style = MaterialTheme.typography.titleMedium); Text(item.location)
                    Text(names[item.categoryId] ?: uncategorized, style = MaterialTheme.typography.bodySmall)
                    item.tags?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            } }
        }
    }
}
@Composable private fun ItemEditor(vm: ItemsViewModel, state: ItemsState, d: ItemDraft, back: () -> Unit) {
    val categories = listOf(null to stringResource(R.string.items_uncategorized)) + state.data.categories.map { it.id to it.name } +
        if (d.categoryId != null && state.data.categories.none { it.id == d.categoryId }) listOf(d.categoryId to stringResource(R.string.items_deleted_category)) else emptyList()
    RecordPage(stringResource(if (d.revision == null) R.string.items_add else R.string.items_edit), back, busy = state.busy,
        action = stringResource(CommonR.string.record_save), actionEnabled = d.valid && !state.loading && !state.failed, onAction = vm::save) {
        item { RecordField(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) }, !state.busy, tag = "item_title") }
        item { RecordField(stringResource(R.string.items_location), d.location, { vm.update(d.copy(location = it)) }, !state.busy, tag = "item_location") }
        item { RecordChoice(stringResource(R.string.items_category), d.categoryId, categories, { vm.update(d.copy(categoryId = it)) }, !state.busy)
            TextButton(onClick = vm::manageCategories, enabled = !state.busy) { Text(stringResource(R.string.items_manage_categories)) } }
        item { RecordField(stringResource(R.string.items_tags), d.tags, { vm.update(d.copy(tags = it)) }, !state.busy) }
        item { RecordField(stringResource(R.string.items_keywords), d.keywords, { vm.update(d.copy(keywords = it)) }, !state.busy) }
        item { RecordField(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, !state.busy, multiline = true) }
        item { PhotoInput(d.image, { photo -> vm.update(d.copy(imageChanged = true, imageAssetId = photo?.assetId, imageContent = photo?.content, hasImage = photo != null)) }, vm.media, vm.processor, state.preferences) }
    }
}
@Composable private fun ItemDetail(vm: ItemsViewModel, state: ItemsState, entry: StoredItem?, back: () -> Unit) {
    var deleting by remember { mutableStateOf<StoredItem?>(null) }; var preview by rememberSaveable { mutableStateOf(false) }
    RecordPage(entry?.title ?: stringResource(R.string.items_title), back, tag = "item_detail", busy = state.busy,
        action = if (entry != null) stringResource(CommonR.string.record_edit) else null, onAction = { entry?.let(vm::edit) }) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            if (entry.hasImage) item { MediaImage(entry.image, vm.media, Modifier.fillMaxWidth().height(240.dp).clickable { preview = true }) }
            item { Text(stringResource(R.string.items_location), style = MaterialTheme.typography.labelLarge); Text(entry.location, style = MaterialTheme.typography.titleMedium) }
            item { Text(state.data.categories.firstOrNull { it.id == entry.categoryId }?.name ?: stringResource(R.string.items_uncategorized)) }
            entry.tags?.let { item { Text(stringResource(R.string.items_tags), style = MaterialTheme.typography.labelLarge); Text(it) } }
            entry.keywords?.let { item { Text(stringResource(R.string.items_keywords), style = MaterialTheme.typography.labelLarge); Text(it) } }
            entry.note?.let { item { Text(stringResource(CommonR.string.record_note), style = MaterialTheme.typography.labelLarge); Text(it) } }
            item { Text(recordDateTime(entry.updatedAt), style = MaterialTheme.typography.bodySmall) }
            item { TextButton(onClick = { deleting = entry }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) } }
        }
    }
    if (preview && entry != null) ImagePreview(listOf(entry.image), 0, vm.media) { preview = false }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.items_delete_note), { deleting = null }) { deleting = null; vm.delete(value) } }
}
