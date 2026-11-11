package imehc.recoow.feature.items

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.items.StoredItem
import imehc.recoow.core.media.ImagePreview
import imehc.recoow.core.media.RecordPhotoSection

@Composable internal fun ItemDetail(vm: ItemsViewModel, state: ItemsState, entry: StoredItem?, back: () -> Unit) {
    var deleting by remember { mutableStateOf<StoredItem?>(null) }
    var preview by rememberSaveable { mutableStateOf(false) }
    val actions = entry?.let {
        listOf(
            RecoowAction(stringResource(CommonR.string.record_edit), enabled = !state.busy) { vm.edit(it) },
            RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy) { deleting = it },
        )
    }.orEmpty()
    RecordPage(stringResource(R.string.items_details), back, tag = "item_detail", busy = state.busy, compact = true, more = actions) {
        if (entry == null) item {
            if (state.failed) RecordListFooter(failed = true, onRetry = vm::retry)
            else if (state.loading) RecordSkeleton(rows = 3)
            else RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_missing)) }
        } else {
            item(key = "head") {
                RecoowGroup {
                    Row(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RecoowLayout.iconTextGap)) {
                        ItemIconTile()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            ItemCategoryBadge(itemCategoryName(entry.categoryId, state.data.categories))
                        }
                    }
                }
            }
            item(key = "location") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.items_location))
                    RecoowGroup {
                        SelectionContainer { Text(entry.location, Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }
            item(key = "memory") {
                val fields = listOfNotNull(
                    entry.tags?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.items_tags) to it },
                    entry.keywords?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.items_keywords) to it },
                    stringResource(R.string.items_updated_at) to recordDateTime(entry.updatedAt),
                )
                Column {
                    RecoowSectionHeader(stringResource(R.string.items_memory))
                    RecoowGroup {
                        fields.forEachIndexed { index, (label, value) ->
                            RecoowTextValueRow(label, value)
                            if (index < fields.lastIndex) RecoowDivider()
                        }
                    }
                }
            }
            entry.note?.takeIf { it.isNotBlank() }?.let { note -> item(key = "note") {
                Column {
                    RecoowSectionHeader(stringResource(CommonR.string.record_note))
                    RecoowGroup {
                        SelectionContainer { Text(note, Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            } }
            if (entry.hasImage) item(key = "photo") { RecordPhotoSection(entry.image, vm.media) { preview = true } }
        }
    }
    if (preview && entry != null) ImagePreview(listOf(entry.image), 0, vm.media) { preview = false }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.items_delete_note), { deleting = null }) {
        deleting = null
        vm.delete(value)
    } }
}
