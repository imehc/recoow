package imehc.recoow.feature.items

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.items.*

@Composable fun ItemsScreen(vm: ItemsViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val managing by vm.managing.collectAsStateWithLifecycle()
    val categoryDraft by vm.categoryDraft.collectAsStateWithLifecycle()
    // 恢复的目的地不能重放其链接而丢弃正在编辑的内容。
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) { vm.show(recordId); deliveredRecordId = recordId }
    }
    val nested = draft != null || detail != null || managing
    val entryDetail = draft == null && !managing && detail != null
    val back = recordBackAction(state.busy, nested, entryDetail, vm::back, onBack)
    BackHandler(nested || state.busy, back)
    when {
        managing -> NamedRecordManager(stringResource(R.string.items_categories), state.data.categories, categoryDraft, state.busy,
            stringResource(R.string.items_delete_category_note), back, vm::editCategory, vm::editCategory, vm::saveCategory, vm::deleteCategory,
            compact = true, rowIcon = CommonR.drawable.ic_folder, emptyDescription = stringResource(R.string.items_empty_categories_hint))
        draft != null -> ItemEditor(vm, state, requireNotNull(draft), back)
        detail != null -> ItemDetail(vm, state, state.data.items.firstOrNull { it.id == detail }, back)
        else -> ItemsList(vm, state, back)
    }
    RecordProblem(state.error, vm::clearError)
}

@Composable private fun ItemsList(vm: ItemsViewModel, state: ItemsState, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.categoryFilter.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var categoryPanel by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.query(""); focus.clearFocus(); keyboard?.hide() }
    val clearFilters: () -> Unit = { vm.filterCategory(null); categoryPanel = false; closeSearch() }
    BackHandler(searching) { closeSearch() }
    val uncategorized = stringResource(R.string.items_uncategorized)
    val names = state.data.categories.associate { it.id to it.name }
    val filtered = state.data.items.filter { item ->
        (category == null || item.categoryId == category) && listOf(item.title, item.location, item.note.orEmpty(), item.tags.orEmpty(), item.keywords.orEmpty(), names[item.categoryId] ?: uncategorized)
            .joinToString(" ").contains(query.trim(), true)
    }
    RecordPage(stringResource(R.string.items_title), back, tag = "tool_itemLocator", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true }, searchTag = "items_search_toggle",
        searchQuery = query, onSearchQuery = vm::query,
        topActions = { ItemCategoryButton(vm::manageCategories, enabled = !state.loading && !state.failed && !state.busy) },
        add = if (!state.failed && !state.loading) stringResource(R.string.items_add) else null, addTag = "items_add", onAdd = { vm.edit() }) {
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading && state.data.items.isEmpty()) item { RecordSkeleton(rows = 4, leading = true) }
        else {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.items_summary))
                    RecoowGroup {
                        RecoowMetricStrip(listOf(
                            RecoowMetric(stringResource(R.string.items_metric_total), state.data.items.size.toString(), icon = CommonR.drawable.ic_tool_items),
                            RecoowMetric(stringResource(R.string.items_categories), state.data.categories.size.toString(), icon = CommonR.drawable.ic_folder),
                            RecoowMetric(stringResource(R.string.items_metric_photos), state.data.items.count { it.hasImage }.toString(), icon = CommonR.drawable.ic_photo),
                            RecoowMetric(stringResource(R.string.items_uncategorized), state.data.items.count { it.categoryId == null }.toString(), icon = CommonR.drawable.ic_tag),
                        ))
                    }
                }
            }
            item(key = "items") {
                RecoowGroup {
                    RecordListHeader(stringResource(R.string.items_record_count, filtered.size), Modifier.padding(horizontal = RecoowLayout.rowHorizontal), action = {
                        Box {
                            TextButton(onClick = { categoryPanel = true }, enabled = !state.busy) {
                                Text(names[category] ?: stringResource(CommonR.string.record_all), Modifier.widthIn(max = 140.dp),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(6.dp))
                                Icon(painterResource(CommonR.drawable.ic_filter), stringResource(R.string.items_filter_category), modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(categoryPanel, { categoryPanel = false }, modifier = Modifier.heightIn(max = 360.dp),
                                shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                val options = listOf(null to stringResource(CommonR.string.record_all)) + state.data.categories.map { it.id to it.name }
                                options.forEach { (id, name) ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { vm.filterCategory(id); categoryPanel = false },
                                        trailingIcon = if (id == category) ({ Icon(painterResource(CommonR.drawable.ic_check), null, modifier = Modifier.size(18.dp)) }) else null)
                                }
                            }
                        }
                    })
                    RecoowDivider(inset = 0.dp)
                    if (filtered.isEmpty()) {
                        val relaxed = state.data.items.isNotEmpty()
                        RecoowEmptyState(
                            title = stringResource(if (relaxed) CommonR.string.record_no_matches else R.string.items_empty),
                            description = stringResource(if (relaxed) CommonR.string.record_no_matches_hint else R.string.items_empty_hint),
                            actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                            onAction = if (relaxed) clearFilters else null,
                        )
                    } else filtered.forEachIndexed { index, item ->
                        ItemListRow(vm, item, itemCategoryName(item.categoryId, state.data.categories))
                        if (index < filtered.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                    }
                }
            }
        }
    }
}

@Composable internal fun ItemCategoryButton(onClick: () -> Unit, enabled: Boolean) {
    IconButton(onClick, enabled = enabled) {
        Icon(painterResource(CommonR.drawable.ic_folder), stringResource(R.string.items_manage_categories),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
