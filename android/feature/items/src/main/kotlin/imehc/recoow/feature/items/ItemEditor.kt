package imehc.recoow.feature.items

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.items.ItemDraft
import imehc.recoow.core.media.PhotoInput

@Composable internal fun ItemEditor(vm: ItemsViewModel, state: ItemsState, d: ItemDraft, back: () -> Unit) {
    val categories = listOf(null to stringResource(R.string.items_uncategorized)) + state.data.categories.map { it.id to it.name } +
        if (d.categoryId != null && state.data.categories.none { it.id == d.categoryId }) listOf(d.categoryId to stringResource(R.string.items_deleted_category)) else emptyList()
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && d.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
    val locationError = if (attempted && d.location.isBlank()) stringResource(CommonR.string.record_field_required) else null
    RecordPage(stringResource(if (d.revision == null) R.string.items_add else R.string.items_edit), back, tag = "item_form", busy = state.busy, compact = true,
        action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check,
        topActions = { ItemCategoryButton(vm::manageCategories, enabled = !state.busy) },
        onAction = { if (d.valid && !state.loading && !state.failed) vm.save() else attempted = true }) {
        item(key = "info") {
            RecordFormSection(stringResource(R.string.items_basic_info)) {
                RecordFormText(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, placeholder = "", error = titleError, tag = "item_title")
                RecordFormDivider()
                RecordChoice(stringResource(R.string.items_category), d.categoryId, categories,
                    { vm.update(d.copy(categoryId = it)) }, enabled = !state.busy, bordered = false)
            }
        }
        item(key = "location") {
            RecordFormSection(stringResource(R.string.items_location_info)) {
                RecordFormText(stringResource(R.string.items_location), d.location, { vm.update(d.copy(location = it)) },
                    enabled = !state.busy, placeholder = "", multiline = true, error = locationError, tag = "item_location")
            }
        }
        item(key = "memory") {
            RecordFormSection(stringResource(R.string.items_memory)) {
                RecordFormText(stringResource(R.string.items_tags), d.tags, { vm.update(d.copy(tags = it)) }, enabled = !state.busy, placeholder = "")
                RecordFormDivider()
                RecordFormText(stringResource(R.string.items_keywords), d.keywords, { vm.update(d.copy(keywords = it)) }, enabled = !state.busy, placeholder = "")
            }
        }
        item(key = "note") {
            RecordFormSection(stringResource(CommonR.string.record_note)) {
                RecordFormText(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) },
                    enabled = !state.busy, multiline = true, placeholder = "")
            }
        }
        item(key = "photo") {
            PhotoInput(d.image, { photo -> vm.update(d.copy(imageChanged = true, imageAssetId = photo?.assetId,
                imageContent = photo?.content, hasImage = photo != null)) }, vm.media, vm.processor, state.preferences, compact = true)
        }
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
}
