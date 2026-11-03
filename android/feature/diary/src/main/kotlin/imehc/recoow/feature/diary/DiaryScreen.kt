package imehc.recoow.feature.diary

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.media.*
import java.time.*

@Composable fun DiaryScreen(vm: DiaryViewModel, onBack: () -> Unit, recordId: String? = null, onSource: (ToolRoute, String) -> Unit = { _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle(); val managing by vm.managing.collectAsStateWithLifecycle()
    val tagDraft by vm.tagDraft.collectAsStateWithLifecycle(); val source by vm.linkSource.collectAsStateWithLifecycle()
    // A restored destination must not replay its link and discard an in-progress editor.
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) { vm.show(recordId); deliveredRecordId = recordId }
    }
    val nested = draft != null || detail != null || managing || source != null
    val back: () -> Unit = { if (!state.busy) { if (nested) vm.back() else onBack() } }
    BackHandler(nested || state.busy, back)
    when {
        managing -> NamedRecordManager(stringResource(R.string.diary_tags), state.data.tags, tagDraft, state.busy,
            stringResource(R.string.diary_delete_tag_note), back, vm::editTag, vm::editTag, vm::saveTag, vm::deleteTag)
        source != null && draft != null -> DiarySourcePicker(vm, requireNotNull(source), requireNotNull(draft), back)
        draft != null -> DiaryEditor(vm, state, requireNotNull(draft), back)
        detail != null -> DiaryDetail(vm, state, state.data.entries.firstOrNull { it.id == detail }, back, onSource)
        else -> DiaryList(vm, state, back)
    }
    RecordProblem(state.error, vm::clearError)
}
@Composable private fun DiaryList(vm: DiaryViewModel, state: DiaryState, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle(); val tag by vm.tagFilter.collectAsStateWithLifecycle(); val date by vm.dateFilter.collectAsStateWithLifecycle()
    var calendar by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var tagPanel by rememberSaveable { mutableStateOf(false) }
    val moods = LocalResources.current.getStringArray(R.array.diary_moods); val zone = ZoneId.systemDefault()
    val photoLabel = stringResource(R.string.diary_photos); val audioLabel = stringResource(R.string.diary_audio)
    val filtered = state.data.entries.filter { entry ->
        (tag == null || entry.tags.any { it.key == tag }) && (date == null || Instant.ofEpochMilli(entry.occurredAt).atZone(zone).toLocalDate().toString() == date) &&
            listOf(entry.title, entry.content, moods[entry.mood.ordinal], state.data.resolvedTags(entry).joinToString { it.value },
                entry.links.joinToString { it.title + " " + it.subtitle.orEmpty() }, entry.attachments.joinToString { it.title ?: if (it.kind == imehc.recoow.core.domain.media.AttachmentKind.PHOTO) photoLabel else audioLabel })
                .joinToString(" ").contains(query.trim(), ignoreCase = true)
    }
    val summary = state.data.summary(LocalDate.now(), zone)
    RecordPage(stringResource(R.string.diary_title), back, tag = "tool_diary", busy = state.busy,
        more = listOf(
            RecoowAction(stringResource(R.string.diary_filter_tags), enabled = !state.loading && !state.failed, onClick = { tagPanel = !tagPanel }),
            RecoowAction(stringResource(R.string.diary_filter_date), enabled = !state.loading && !state.failed, onClick = { calendar = true }),
            RecoowAction(stringResource(R.string.diary_tags), enabled = !state.loading && !state.failed, onClick = vm::manageTags),
        ),
        searching = searching, onSearchToggle = { searching = !searching }, searchTag = "diary_search_toggle",
        add = if (!state.failed && !state.loading) stringResource(R.string.diary_add) else null, onAdd = { vm.edit() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item {
                Column {
                    RecoowSectionHeader(stringResource(CommonR.string.record_overview))
                    RecoowGroup {
                        RecoowMetricGrid(
                            listOf(
                                RecoowMetric(stringResource(R.string.diary_metric_total), summary.total.toString()),
                                RecoowMetric(stringResource(R.string.diary_metric_today), summary.today.toString()),
                                RecoowMetric(stringResource(R.string.diary_metric_days), summary.activeDays.toString()),
                                RecoowMetric(stringResource(R.string.diary_metric_streak), summary.streak.toString()),
                            ),
                        )
                    }
                }
            }
            if (searching) item { RecordSearchField(query, vm::query, tag = "diary_search") }
            if (tag != null || date != null) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tag != null) FilterChip(true, onClick = { vm.filterTag(null) }, label = { Text(tag.orEmpty()) })
                    if (date != null) FilterChip(true, onClick = { vm.filterDate(null) }, label = { Text(date.orEmpty()) })
                }
            }
            if (tagPanel) item {
                RecoowGroup {
                    RecordChoice(stringResource(R.string.diary_tags), tag,
                        listOf(null to stringResource(CommonR.string.record_all)) + state.data.tagReferences().map { it.key to it.value }, vm::filterTag, bordered = false)
                    RecoowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.filterTag(null); tagPanel = false }) { Text(stringResource(CommonR.string.record_clear_filters)) }
                    }
                }
            }
            if (filtered.isEmpty()) item {
                val relaxed = state.data.entries.isNotEmpty()
                RecoowGroup {
                    RecoowEmptyState(
                        title = stringResource(if (relaxed) CommonR.string.record_no_matches else CommonR.string.record_empty),
                        actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (relaxed) ({ vm.query(""); vm.filterTag(null); vm.filterDate(null) }) else null,
                    )
                }
            }
            if (filtered.isNotEmpty()) item(key = "entries") {
                RecoowGroup {
                    filtered.forEachIndexed { index, entry ->
                        DiaryRow(entry, moods[entry.mood.ordinal], state, vm)
                        if (index < filtered.lastIndex) RecoowDivider()
                    }
                }
            }
        }
    }
    if (calendar) RecordDatePicker(date?.let(LocalDate::parse) ?: LocalDate.now(), { calendar = false }) { vm.filterDate(it.toString()); calendar = false }
}

/** 日记列表行：标题 + 正文摘要 + 元信息，比通用行多一行正文才有用。 */
@Composable private fun DiaryRow(entry: DiaryEntry, mood: String, state: DiaryState, vm: DiaryViewModel) {
    Column(Modifier.fillMaxWidth().clickable { vm.show(entry.id) }.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(entry.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(recordDateTime(entry.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.content.isNotBlank()) Text(
            entry.content,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        val meta = buildList {
            add(mood)
            if (entry.tags.isNotEmpty()) add(state.data.resolvedTags(entry).joinToString(" ") { it.value })
            if (entry.links.isNotEmpty() || entry.attachments.isNotEmpty()) add(stringResource(R.string.diary_relations_count, entry.links.size, entry.attachments.size))
        }.joinToString(" · ")
        Text(meta, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun DiaryEditor(vm: DiaryViewModel, state: DiaryState, d: DiaryDraft, back: () -> Unit) {
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.locate() else vm.locationDenied()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.cancelLocation() }
    DisposableEffect(Unit) { onDispose { vm.cancelLocation() } }
    val moods = LocalResources.current.getStringArray(R.array.diary_moods)
    val defaultTitle = stringResource(R.string.diary_title)
    val available = (state.data.tagReferences() + d.tags).distinctBy { it.key }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val emptyError = if (attempted && !d.valid) stringResource(R.string.diary_need_content) else null
    RecordPage(stringResource(if (d.revision == null) R.string.diary_add else R.string.diary_edit), back, busy = state.busy,
        more = listOf(
            RecoowAction(stringResource(R.string.diary_manage_tags), enabled = !state.busy, onClick = vm::manageTags),
            if (d.latitude == null) RecoowAction(stringResource(if (state.locating) R.string.diary_locating else R.string.diary_capture_location),
                enabled = !state.busy && !state.locating, onClick = { locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) })
            else RecoowAction(stringResource(R.string.diary_remove_location), enabled = !state.busy, onClick = { vm.update(d.copy(latitude = null, longitude = null, accuracy = null)) }),
        ),
        action = stringResource(CommonR.string.record_save), onAction = { if (d.valid && !state.failed && !state.loading) vm.save(defaultTitle) else attempted = true }) {
        item {
            RecordForm {
                RecordFormText(stringResource(R.string.diary_entry_title), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, error = emptyError, tag = "diary_title")
                RecordFormDivider()
                RecordFormText(stringResource(R.string.diary_content), d.content, { vm.update(d.copy(content = it)) },
                    enabled = !state.busy, multiline = true, tag = "diary_content")
                RecordFormDivider()
                RecordFormDateTime(stringResource(CommonR.string.record_date), d.occurredAt, { vm.update(d.copy(occurredAt = it)) }, !state.busy)
                RecordFormDivider()
                RecordChoice(stringResource(R.string.diary_mood), d.mood, DiaryMood.entries.map { it to moods[it.ordinal] },
                    { vm.update(d.copy(mood = it)) }, enabled = !state.busy, bordered = false)
            }
        }
        item {
            RecordForm {
                if (available.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { available.forEach { ref ->
                            FilterChip(d.tags.any { it.key == ref.key }, { vm.update(d.copy(tags = if (d.tags.any { it.key == ref.key }) d.tags.filterNot { it.key == ref.key } else d.tags + ref)) }, label = { Text(ref.value) }, enabled = !state.busy)
                        } }
                    }
                    RecordFormDivider()
                }
                RecordFormAction(stringResource(R.string.diary_select_links), onClick = { vm.selectSource(DiarySource.BILL) }, enabled = !state.busy)
            }
        }
        items(d.links, key = { "link:${it.key}" }) { link ->
            RecordForm {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(link.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        link.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Text(stringResource(CommonR.string.record_remove), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(enabled = !state.busy) { vm.update(d.copy(links = d.links.filterNot { it.key == link.key })) }
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                }
            }
        }
        if (d.latitude != null && d.longitude != null) item {
            RecordForm {
                RecordFormValue(stringResource(R.string.diary_location_label), stringResource(R.string.diary_location, requireNotNull(d.latitude), requireNotNull(d.longitude)))
            }
        }
        item { AttachmentInput(d.attachments, { vm.update(d.copy(attachments = it)) }, vm.media, vm.processor, state.preferences) }
    }
}
@Composable private fun DiarySourcePicker(vm: DiaryViewModel, source: DiarySource, d: DiaryDraft, back: () -> Unit) {
    val values by vm.sources.collectAsStateWithLifecycle(); val loading by vm.sourceLoading.collectAsStateWithLifecycle(); val failed by vm.sourceFailed.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }; val names = LocalResources.current.getStringArray(R.array.diary_sources)
    val filtered = values.filter { (it.title + " " + it.subtitle.orEmpty()).contains(query.trim(), true) }
    RecordPage(stringResource(R.string.diary_select_links), back, action = stringResource(CommonR.string.record_confirm), onAction = back) {
        item { RecordChoice(stringResource(R.string.diary_source), source, DiarySource.entries.map { it to names[it.ordinal] }, vm::selectSource) }
        item { RecordField(stringResource(CommonR.string.record_search), query, { query = it }) }
        item { RecordLoadState(loading, failed) { vm.selectSource(source) } }
        if (!loading && !failed && filtered.isEmpty()) item { Text(stringResource(CommonR.string.record_no_matches)) }
        items(filtered, key = { it.key }) { link -> val selected = d.links.any { it.key == link.key }
            OutlinedCard(onClick = { vm.update(d.copy(links = if (selected) d.links.filterNot { it.key == link.key } else d.links + link)) }, modifier = Modifier.fillMaxWidth().recordToggleState(selected)) {
                Row(Modifier.padding(12.dp)) { Checkbox(selected, null); Column(Modifier.weight(1f).padding(8.dp)) {
                    Text(link.title); link.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    link.occurredAt?.let { Text(recordDateTime(it), style = MaterialTheme.typography.bodySmall) }
                } }
            }
        }
    }
}
@Composable private fun DiaryDetail(vm: DiaryViewModel, state: DiaryState, entry: DiaryEntry?, back: () -> Unit, onSource: (ToolRoute, String) -> Unit) {
    var deleting by remember { mutableStateOf<DiaryEntry?>(null) }
    val moods = LocalResources.current.getStringArray(R.array.diary_moods)
    val sourceNames = LocalResources.current.getStringArray(R.array.diary_sources)
    RecordPage(entry?.title ?: stringResource(R.string.diary_title), back, tag = "diary_detail", busy = state.busy,
        action = if (entry != null) stringResource(CommonR.string.record_edit) else null, onAction = { entry?.let(vm::edit) },
        more = if (entry != null) listOf(RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy, onClick = { deleting = entry })) else emptyList()) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item { Text(recordDateTime(entry.occurredAt) + " · " + moods[entry.mood.ordinal], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (entry.tags.isNotEmpty()) item { Text(state.data.resolvedTags(entry).joinToString(" · ") { it.value }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                RecoowGroup {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(entry.content, modifier = Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (entry.latitude != null && entry.longitude != null) item {
                RecoowGroup { RecoowRow(title = stringResource(R.string.diary_location, requireNotNull(entry.latitude), requireNotNull(entry.longitude))) }
            }
            if (entry.links.isNotEmpty()) item {
                Column {
                    RecoowSectionHeader(stringResource(R.string.diary_links))
                    RecoowGroup {
                        entry.links.forEachIndexed { index, link ->
                            val route = when (link.sourceType) { "bill" -> ToolRoute.BILLS; "reminder" -> ToolRoute.REMINDERS; "storedItem" -> ToolRoute.ITEMS; "anniversary" -> ToolRoute.ANNIVERSARIES; "decisionChoice" -> ToolRoute.DECISION; "track" -> ToolRoute.LOCATION; else -> null }
                            val sourceName = DiarySource.entries.firstOrNull { it.wire == link.sourceType }?.let { sourceNames[it.ordinal] }
                            val action: (() -> Unit)? = if (route != null) ({ onSource(route, link.sourceId) }) else null
                            RecoowRow(
                                title = link.title,
                                subtitle = listOfNotNull(sourceName, link.subtitle, link.occurredAt?.let { recordDateTime(it) }).joinToString(" · "),
                                showChevron = action != null,
                                onClick = action,
                            )
                            if (index < entry.links.lastIndex) RecoowDivider()
                        }
                    }
                }
            }
            item { AttachmentGallery(entry.attachments, vm.media) }
        }
    }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.diary_delete_note), { deleting = null }) { deleting = null; vm.delete(value) } }
}
