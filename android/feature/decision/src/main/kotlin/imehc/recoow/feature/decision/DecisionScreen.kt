package imehc.recoow.feature.decision

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.decision.*
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.core.media.*
import imehc.recoow.core.scene.*

@Composable fun DecisionScreen(vm: DecisionViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle(); val collection by vm.collection.collectAsStateWithLifecycle()
    val collectionDraft by vm.collectionDraft.collectAsStateWithLifecycle(); val optionDraft by vm.optionDraft.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(); val detail by vm.detail.collectAsStateWithLifecycle()
    var delivered by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) { if (recordId != null && recordId != delivered) { vm.show(recordId); delivered = recordId } }
    val context = LocalContext.current
    LaunchedEffect(context) { runCatching { prewarmDecisionScene(context) } }
    var models by rememberSaveable { mutableStateOf(false) }
    val dark = when (state.preferences.appearance) { Appearance.DARK -> true; Appearance.LIGHT -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val back = recordBackAction(state.busy, models || collection != null || collectionDraft != null || optionDraft != null || history || detail != null,
        entryDetail = !models && collectionDraft == null && optionDraft == null && detail != null, onNestedBack = { if (models) models = false else vm.back() }, onBack = onBack)
    BackHandler(models || collection != null || collectionDraft != null || optionDraft != null || history || detail != null || state.busy, back)
    var focusRequest by rememberSaveable { mutableStateOf<String?>(null) }
    when {
        models -> DecisionModelPreview(dark, back)
        optionDraft != null -> OptionEditor(vm, state, requireNotNull(optionDraft), back,
            onDelete = vm::deleteOption,
            onFocus = { option -> focusRequest = option.id; back() })
        collectionDraft != null -> {
            val d = requireNotNull(collectionDraft)
            var attempted by rememberSaveable { mutableStateOf(false) }
            val titleError = if (attempted && d.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
            RecordPage(stringResource(R.string.decision_collection), back, busy = state.busy, compact = true, action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check,
                onAction = { if (d.title.isNotBlank()) vm.saveCollection() else attempted = true }) {
                item {
                    RecordFormSection(stringResource(R.string.decision_basic_info)) {
                        RecordFormText(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) },
                            enabled = !state.busy, placeholder = "", error = titleError, tag = "decision_collection_title")
                    }
                }
                item {
                    RecordFormSection(stringResource(CommonR.string.record_note)) {
                        RecordFormText(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) },
                            enabled = !state.busy, multiline = true, placeholder = "", showLabel = false)
                    }
                }
            }
        }
        detail != null -> ChoiceDetail(vm, state, state.data.history.firstOrNull { it.id == detail }, back)
        history -> ChoiceHistory(vm, state, collection, back)
        collection != null -> DecisionStage(vm, state, state.data.collections.firstOrNull { it.id == collection }, back,
            dark = dark, onModels = { models = true }, focusRequest = focusRequest, onFocusConsumed = { focusRequest = null })
        else -> Collections(vm, state, back, onModels = { models = true })
    }
    RecordProblem(state.error, vm::clearError)
}
@Composable private fun Collections(vm: DecisionViewModel, state: DecisionState, back: () -> Unit, onModels: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.query(""); focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching) { closeSearch() }
    val rows = state.data.collections.filter { (it.title + " " + it.note.orEmpty()).contains(query.trim(), true) }
    RecordPage(stringResource(R.string.decision_title), back, tag = "tool_decisionMaker", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true }, searchTag = "decision_search_toggle",
        searchQuery = query, onSearchQuery = vm::query,
        more = listOf(RecoowAction(stringResource(R.string.decision_history), enabled = !state.busy, onClick = vm::showHistory),
            RecoowAction(stringResource(R.string.decision_models), enabled = !state.busy, onClick = onModels)),
        add = if (!state.loading && !state.failed) stringResource(R.string.decision_add_collection) else null,
        addTag = "decision_add_collection", onAdd = { vm.editCollection() }) {
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading && state.data.collections.isEmpty()) item { RecordSkeleton(rows = 4, leading = true) }
        else {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.decision_summary))
                    RecoowGroup {
                        RecoowMetricStrip(listOf(
                            RecoowMetric(stringResource(R.string.decision_collection), state.data.collections.size.toString(), icon = CommonR.drawable.ic_folder),
                            RecoowMetric(stringResource(R.string.decision_enabled_value), state.data.options.count { it.enabled }.toString(), icon = CommonR.drawable.ic_tool_decision),
                            RecoowMetric(stringResource(R.string.decision_history), state.data.history.size.toString(), icon = CommonR.drawable.ic_clock),
                        ))
                    }
                }
            }
            item(key = "collections") {
                RecoowGroup {
                    RecordListHeader(stringResource(R.string.decision_collection_count, rows.size), Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
                    RecoowDivider(inset = 0.dp)
                    if (rows.isEmpty()) {
                        val filtered = state.data.collections.isNotEmpty()
                        RecoowEmptyState(title = stringResource(if (filtered) CommonR.string.record_no_matches else R.string.decision_empty_title),
                            description = stringResource(if (filtered) CommonR.string.record_no_matches_hint else R.string.decision_empty),
                            actionLabel = if (filtered) stringResource(CommonR.string.record_clear_filters) else null,
                            onAction = if (filtered) closeSearch else null)
                    } else rows.forEachIndexed { index, collection ->
                        val options = state.data.options.filter { it.collectionId == collection.id }
                        val photo = options.firstOrNull { it.enabled && it.hasImage } ?: options.firstOrNull { it.hasImage }
                        val tint = RecoowToolIcons.tint(imehc.recoow.core.domain.navigation.ToolRoute.DECISION)
                        RecordCoverRow(locator = photo?.image, repository = vm.media, icon = CommonR.drawable.ic_tool_decision, tint = tint.copy(alpha = 0.12f),
                            iconContent = { Icon(painterResource(CommonR.drawable.ic_tool_decision), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize)) },
                            onClick = { vm.open(collection.id) }, modifier = Modifier.testTag("decision_${collection.id}"),
                            trailing = { Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) { RecoowChevron() } }) {
                            Text(collection.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.decision_option_count, options.count { it.enabled }),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index < rows.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                    }
                }
            }
        }
    }
}
@Composable private fun OptionEditor(vm: DecisionViewModel, state: DecisionState, d: OptionDraft, back: () -> Unit,
    onDelete: (DecisionOption) -> Unit = {}, onFocus: (DecisionOption) -> Unit = {}) {
    val weight = d.weight.toLongOrNull()
    val original = state.data.options.firstOrNull { it.id == d.id }
    val valid = d.valid && (weight in 1L..100L || weight == original?.weight)
    var attempted by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && d.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
    val weightError = if (attempted && !(weight in 1L..100L || weight == original?.weight)) stringResource(R.string.decision_weight_invalid) else null
    RecordPage(stringResource(R.string.decision_option), back, tag = "decision_option_form", busy = state.busy, compact = true,
        action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check,
        more = if (original != null) listOfNotNull(
            if (original.enabled) RecoowAction(stringResource(R.string.decision_focus), enabled = !state.busy) { onFocus(original) } else null,
            RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy) { deleting = true },
        ) else emptyList(),
        onAction = { if (valid && !state.failed && !state.loading) vm.saveOption() else attempted = true }) {
        item(key = "info") {
            RecordFormSection(stringResource(R.string.decision_basic_info)) {
                RecordFormText(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, placeholder = "", error = titleError, tag = "decision_option_title")
            }
        }
        item(key = "rules") {
            RecordFormSection(stringResource(R.string.decision_rules)) {
                RecordFormText(stringResource(R.string.decision_weight), d.weight, { vm.update(d.copy(weight = it)) },
                    enabled = !state.busy, keyboard = androidx.compose.ui.text.input.KeyboardType.Number, error = weightError, tag = "decision_weight")
                RecordFormDivider()
                RecordSwitch(stringResource(R.string.decision_enabled), d.enabled, { vm.update(d.copy(enabled = it)) },
                    enabled = !state.busy, modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
            }
        }
        item(key = "detail") {
            RecordFormSection(stringResource(R.string.decision_description)) {
                RecordFormText(stringResource(R.string.decision_description), d.detail, { vm.update(d.copy(detail = it)) },
                    enabled = !state.busy, multiline = true, placeholder = "", showLabel = false)
            }
        }
        item(key = "custom") {
            RecordFormSection(stringResource(R.string.decision_custom)) {
                RecordFormText(stringResource(R.string.decision_custom), d.customInfo, { vm.update(d.copy(customInfo = it)) },
                    enabled = !state.busy, multiline = true, placeholder = "", showLabel = false)
            }
        }
        item(key = "photo") {
            PhotoInput(d.image, { photo -> vm.update(d.copy(imageChanged = true, imageAssetId = photo?.assetId, imageContent = photo?.content, hasImage = photo != null)) },
                vm.media, vm.processor, state.preferences, compact = true)
        }
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
    if (deleting && original != null) RecordDeleteConfirmation(original.title, stringResource(R.string.decision_delete_option), { deleting = false }) {
        deleting = false; onDelete(original)
    }
}
@Composable private fun ChoiceHistory(vm: DecisionViewModel, state: DecisionState, collection: String?, back: () -> Unit) {
    val query by vm.historyQuery.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.queryHistory(""); focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching) { closeSearch() }
    val source = state.data.history.filter { collection == null || it.collectionId == collection }
    val records = source.filter { listOf(it.title, it.collectionTitle, it.detail.orEmpty(), it.customInfo.orEmpty()).joinToString(" ").contains(query.trim(), true) }
    RecordPage(stringResource(R.string.decision_history), back, tag = "decision_history", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true }, searchTag = "decision_history_search_toggle",
        searchQuery = query, onSearchQuery = vm::queryHistory) {
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading && source.isEmpty()) item { RecordSkeleton(rows = 4, leading = true) }
        else item(key = "rows") {
            RecoowGroup {
                RecordListHeader(stringResource(R.string.decision_history_count, records.size), Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
                RecoowDivider(inset = 0.dp)
                if (records.isEmpty()) {
                    val filtered = source.isNotEmpty()
                    RecoowEmptyState(stringResource(if (filtered) CommonR.string.record_no_matches else CommonR.string.record_empty),
                        description = stringResource(if (filtered) CommonR.string.record_no_matches_hint else R.string.decision_history_hint),
                        actionLabel = if (filtered) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (filtered) closeSearch else null)
                } else records.forEachIndexed { index, record ->
                    DecisionChoiceRow(vm, record, enabled = !state.busy) { vm.show(record.id) }
                    if (index < records.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                }
            }
        }
    }
}

@Composable private fun ChoiceDetail(vm: DecisionViewModel, state: DecisionState, record: DecisionChoice?, back: () -> Unit) {
    var deleting by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    RecordPage(stringResource(R.string.decision_choice_details), back, tag = "decision_detail", busy = state.busy, compact = true,
        more = if (record != null) listOf(RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy) { deleting = true }) else emptyList()) {
        if (record == null) item(key = "missing") {
            if (state.failed) RecordListFooter(failed = true, onRetry = vm::retry)
            else if (state.loading) RecordSkeleton(rows = 3)
            else RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_missing)) }
        } else {
            item(key = "head") {
                RecoowGroup {
                    Row(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RecoowLayout.iconTextGap)) {
                        val tint = RecoowToolIcons.tint(imehc.recoow.core.domain.navigation.ToolRoute.DECISION)
                        Box(Modifier.size(RecoowLayout.listCoverSize).clip(RoundedCornerShape(RecoowLayout.thumbnailRadius))
                            .background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(CommonR.drawable.ic_tool_decision), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize))
                        }
                        Text(record.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item(key = "facts") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.decision_record_info))
                    RecoowGroup {
                        RecoowTextValueRow(stringResource(R.string.decision_collection), record.collectionTitle)
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.decision_selected_at), recordDateTime(record.selectedAt))
                    }
                }
            }
            record.detail?.takeIf { it.isNotBlank() }?.let { text -> item(key = "detail") { DecisionTextSection(stringResource(R.string.decision_description), text) } }
            record.customInfo?.takeIf { it.isNotBlank() }?.let { text -> item(key = "custom") { DecisionTextSection(stringResource(R.string.decision_custom), text) } }
            if (record.hasImage) item(key = "image") { RecordPhotoSection(record.image, vm.media) { preview = true } }
        }
    }
    if (preview && record != null) ImagePreview(listOf(record.image), 0, vm.media) { preview = false }
    if (deleting && record != null) RecordDeleteConfirmation(record.title, stringResource(R.string.decision_delete_history), { deleting = false }) {
        deleting = false; vm.deleteChoice(record)
    }
}
