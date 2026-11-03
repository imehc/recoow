package imehc.recoow.feature.items

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var searching by rememberSaveable { mutableStateOf(false) }
    var categoryPanel by rememberSaveable { mutableStateOf(false) }
    val uncategorized = stringResource(R.string.items_uncategorized)
    val names = state.data.categories.associate { it.id to it.name }
    val filtered = state.data.items.filter { item ->
        (category == null || item.categoryId == category) && listOf(item.title, item.location, item.note.orEmpty(), item.tags.orEmpty(), item.keywords.orEmpty(), names[item.categoryId] ?: uncategorized)
            .joinToString(" ").contains(query.trim(), true)
    }
    RecordPage(stringResource(R.string.items_title), back, tag = "tool_itemLocator", busy = state.busy,
        more = listOf(
            RecoowAction(stringResource(R.string.items_filter_category), enabled = !state.loading && !state.failed, onClick = { categoryPanel = !categoryPanel }),
            RecoowAction(stringResource(R.string.items_categories), enabled = !state.loading && !state.failed, onClick = vm::manageCategories),
        ),
        searching = searching, onSearchToggle = { searching = !searching }, searchTag = "items_search_toggle",
        add = if (!state.failed && !state.loading) stringResource(R.string.items_add) else null, onAdd = { vm.edit() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            if (searching) item { RecordSearchField(query, vm::query, tag = "items_search") }
            if (category != null) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(true, onClick = { vm.filterCategory(null) },
                        label = { Text(names[category] ?: stringResource(R.string.items_uncategorized)) })
                }
            }
            if (categoryPanel) item {
                RecoowGroup {
                    RecordChoice(stringResource(R.string.items_category), category,
                        listOf(null to stringResource(CommonR.string.record_all)) + state.data.categories.map { it.id to it.name }, vm::filterCategory, bordered = false)
                    RecoowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.filterCategory(null); categoryPanel = false }) { Text(stringResource(CommonR.string.record_clear_filters)) }
                    }
                }
            }
            if (filtered.isEmpty()) item {
                val relaxed = state.data.items.isNotEmpty()
                RecoowGroup {
                    RecoowEmptyState(
                        title = stringResource(if (relaxed) CommonR.string.record_no_matches else CommonR.string.record_empty),
                        actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (relaxed) ({ vm.query(""); vm.filterCategory(null) }) else null,
                    )
                }
            }
            if (filtered.isNotEmpty()) item(key = "items") {
                RecoowGroup {
                    filtered.forEachIndexed { index, item ->
                        RecoowRow(
                            title = item.title,
                            subtitle = listOfNotNull(item.location, names[item.categoryId] ?: uncategorized, item.tags?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            showChevron = true,
                            onClick = { vm.show(item.id) },
                        )
                        if (index < filtered.lastIndex) RecoowDivider()
                    }
                }
            }
        }
    }
}
@Composable private fun ItemEditor(vm: ItemsViewModel, state: ItemsState, d: ItemDraft, back: () -> Unit) {
    val categories = listOf(null to stringResource(R.string.items_uncategorized)) + state.data.categories.map { it.id to it.name } +
        if (d.categoryId != null && state.data.categories.none { it.id == d.categoryId }) listOf(d.categoryId to stringResource(R.string.items_deleted_category)) else emptyList()
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && d.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
    val locationError = if (attempted && d.location.isBlank()) stringResource(CommonR.string.record_field_required) else null
    RecordPage(stringResource(if (d.revision == null) R.string.items_add else R.string.items_edit), back, busy = state.busy,
        action = stringResource(CommonR.string.record_save), onAction = { if (d.valid && !state.loading && !state.failed) vm.save() else attempted = true }) {
        item {
            RecordForm {
                RecordFormText(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, error = titleError, tag = "item_title")
                RecordFormDivider()
                RecordFormText(stringResource(R.string.items_location), d.location, { vm.update(d.copy(location = it)) },
                    enabled = !state.busy, error = locationError, tag = "item_location")
                RecordFormDivider()
                RecordChoice(stringResource(R.string.items_category), d.categoryId, categories,
                    { vm.update(d.copy(categoryId = it)) }, enabled = !state.busy, bordered = false)
                RecordFormDivider()
                RecordFormAction(stringResource(R.string.items_manage_categories), onClick = vm::manageCategories, enabled = !state.busy)
                RecordFormDivider()
                RecordFormText(stringResource(R.string.items_tags), d.tags, { vm.update(d.copy(tags = it)) }, enabled = !state.busy)
                RecordFormDivider()
                RecordFormText(stringResource(R.string.items_keywords), d.keywords, { vm.update(d.copy(keywords = it)) }, enabled = !state.busy)
                RecordFormDivider()
                RecordFormText(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, enabled = !state.busy, multiline = true)
            }
        }
        item { PhotoInput(d.image, { photo -> vm.update(d.copy(imageChanged = true, imageAssetId = photo?.assetId, imageContent = photo?.content, hasImage = photo != null)) }, vm.media, vm.processor, state.preferences) }
    }
}
@Composable private fun ItemDetail(vm: ItemsViewModel, state: ItemsState, entry: StoredItem?, back: () -> Unit) {
    var deleting by remember { mutableStateOf<StoredItem?>(null) }; var preview by rememberSaveable { mutableStateOf(false) }
    RecordPage(entry?.title ?: stringResource(R.string.items_title), back, tag = "item_detail", busy = state.busy,
        action = if (entry != null) stringResource(CommonR.string.record_edit) else null, onAction = { entry?.let(vm::edit) },
        more = if (entry != null) listOf(RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy, onClick = { deleting = entry })) else emptyList()) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            if (entry.hasImage) item {
                MediaImage(entry.image, vm.media, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(RecoowLayout.cardRadius)).clickable { preview = true })
            }
            item {
                RecoowGroup {
                    RecoowRow(title = stringResource(R.string.items_location), trailingText = entry.location)
                    RecoowDivider()
                    RecoowRow(title = stringResource(R.string.items_category), trailingText = state.data.categories.firstOrNull { it.id == entry.categoryId }?.name ?: stringResource(R.string.items_uncategorized))
                    entry.tags?.takeIf { it.isNotBlank() }?.let { RecoowDivider(); RecoowRow(title = stringResource(R.string.items_tags), trailingText = it) }
                    entry.keywords?.takeIf { it.isNotBlank() }?.let { RecoowDivider(); RecoowRow(title = stringResource(R.string.items_keywords), trailingText = it) }
                    RecoowDivider()
                    RecoowRow(title = stringResource(CommonR.string.record_date), trailingText = recordDateTime(entry.updatedAt))
                }
            }
            entry.note?.takeIf { it.isNotBlank() }?.let { note -> item {
                Column {
                    RecoowSectionHeader(stringResource(CommonR.string.record_note))
                    RecoowGroup {
                        Text(note, modifier = Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            } }
        }
    }
    if (preview && entry != null) ImagePreview(listOf(entry.image), 0, vm.media) { preview = false }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.items_delete_note), { deleting = null }) { deleting = null; vm.delete(value) } }
}
