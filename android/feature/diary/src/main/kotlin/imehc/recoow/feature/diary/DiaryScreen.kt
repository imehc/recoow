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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
        action = stringResource(R.string.diary_tags), actionEnabled = !state.loading && !state.failed, onAction = vm::manageTags,
        add = if (!state.failed && !state.loading) stringResource(R.string.diary_add) else null, onAdd = { vm.edit() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.diary_summary, summary.total, summary.today, summary.activeDays, summary.streak))
            } } }
            item { RecordField(stringResource(CommonR.string.record_search), query, vm::query, tag = "diary_search") }
            item { RecordChoice(stringResource(R.string.diary_tags), tag,
                listOf(null to stringResource(CommonR.string.record_all)) + state.data.tagReferences().map { it.key to it.value }, vm::filterTag) }
            item { FlowRow { OutlinedButton(onClick = { calendar = true }) { Text(date ?: stringResource(R.string.diary_filter_date)) }
                if (date != null) TextButton(onClick = { vm.filterDate(null) }) { Text(stringResource(R.string.diary_clear_date)) } } }
            if (filtered.isEmpty()) item { Text(stringResource(if (state.data.entries.isEmpty()) CommonR.string.record_empty else CommonR.string.record_no_matches)) }
            items(filtered, key = { it.id }) { entry -> Card(Modifier.fillMaxWidth().clickable { vm.show(entry.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(recordDateTime(entry.occurredAt) + " · " + moods[entry.mood.ordinal], style = MaterialTheme.typography.bodySmall)
                    if (entry.content.isNotBlank()) Text(entry.content, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (entry.tags.isNotEmpty()) Text(state.data.resolvedTags(entry).joinToString(" · ") { it.value })
                    if (entry.links.isNotEmpty() || entry.attachments.isNotEmpty()) Text(stringResource(R.string.diary_relations_count, entry.links.size, entry.attachments.size), style = MaterialTheme.typography.bodySmall)
                }
            } }
        }
    }
    if (calendar) RecordDatePicker(date?.let(LocalDate::parse) ?: LocalDate.now(), { calendar = false }) { vm.filterDate(it.toString()); calendar = false }
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
    RecordPage(stringResource(if (d.revision == null) R.string.diary_add else R.string.diary_edit), back, busy = state.busy,
        action = stringResource(CommonR.string.record_save), actionEnabled = d.valid && !state.failed && !state.loading, onAction = { vm.save(defaultTitle) }) {
        item { RecordField(stringResource(R.string.diary_entry_title), d.title, { vm.update(d.copy(title = it)) }, !state.busy, tag = "diary_title") }
        item { RecordField(stringResource(R.string.diary_content), d.content, { vm.update(d.copy(content = it)) }, !state.busy, multiline = true, tag = "diary_content") }
        item { RecordDateTime(stringResource(CommonR.string.record_date), d.occurredAt, { vm.update(d.copy(occurredAt = it)) }, !state.busy) }
        item { RecordChoice(stringResource(R.string.diary_mood), d.mood, DiaryMood.entries.map { it to moods[it.ordinal] }, { vm.update(d.copy(mood = it)) }, !state.busy) }
        item { Text(stringResource(R.string.diary_tags), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { available.forEach { ref ->
                FilterChip(d.tags.any { it.key == ref.key }, { vm.update(d.copy(tags = if (d.tags.any { it.key == ref.key }) d.tags.filterNot { it.key == ref.key } else d.tags + ref)) }, label = { Text(ref.value) }, enabled = !state.busy)
            } }
            TextButton(onClick = vm::manageTags, enabled = !state.busy) { Text(stringResource(R.string.diary_manage_tags)) }
        }
        item { Text(stringResource(R.string.diary_links), style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(onClick = { vm.selectSource(DiarySource.BILL) }, enabled = !state.busy) { Text(stringResource(R.string.diary_select_links)) } }
        items(d.links, key = { "link:${it.key}" }) { link -> OutlinedCard { Column(Modifier.padding(12.dp)) {
            Text(link.title); link.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { vm.update(d.copy(links = d.links.filterNot { it.key == link.key })) }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_remove)) }
        } } }
        if (d.latitude != null && d.longitude != null) item { Text(stringResource(R.string.diary_location, requireNotNull(d.latitude), requireNotNull(d.longitude)))
            TextButton(onClick = { vm.update(d.copy(latitude = null, longitude = null, accuracy = null)) }, enabled = !state.busy) { Text(stringResource(R.string.diary_remove_location)) } }
        item { FilledTonalButton(onClick = { locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }, enabled = !state.busy && !state.locating) {
            Text(stringResource(if (state.locating) R.string.diary_locating else R.string.diary_capture_location))
        } }
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
            OutlinedCard(onClick = { vm.update(d.copy(links = if (selected) d.links.filterNot { it.key == link.key } else d.links + link)) }, modifier = Modifier.fillMaxWidth()) {
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
        action = if (entry != null) stringResource(CommonR.string.record_edit) else null, onAction = { entry?.let(vm::edit) }) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item { Text(recordDateTime(entry.occurredAt) + " · " + moods[entry.mood.ordinal]) }
            if (entry.tags.isNotEmpty()) item { Text(state.data.resolvedTags(entry).joinToString(" · ") { it.value }) }
            item { androidx.compose.foundation.text.selection.SelectionContainer { Text(entry.content) } }
            if (entry.latitude != null && entry.longitude != null) item { Text(stringResource(R.string.diary_location, requireNotNull(entry.latitude), requireNotNull(entry.longitude))) }
            if (entry.links.isNotEmpty()) item { Text(stringResource(R.string.diary_links), style = MaterialTheme.typography.titleMedium) }
            items(entry.links, key = { it.key }) { link ->
                val route = when (link.sourceType) { "bill" -> ToolRoute.BILLS; "reminder" -> ToolRoute.REMINDERS; "storedItem" -> ToolRoute.ITEMS; "anniversary" -> ToolRoute.ANNIVERSARIES; "decisionChoice" -> ToolRoute.DECISION; else -> null }
                OutlinedCard(Modifier.fillMaxWidth().then(if (route != null) Modifier.clickable { onSource(route, link.sourceId) } else Modifier)) { Column(Modifier.padding(16.dp)) {
                DiarySource.entries.firstOrNull { it.wire == link.sourceType }?.let { Text(sourceNames[it.ordinal], style = MaterialTheme.typography.labelMedium) }
                Text(link.title, style = MaterialTheme.typography.titleMedium); link.subtitle?.let { Text(it) }
                link.occurredAt?.let { Text(recordDateTime(it), style = MaterialTheme.typography.bodySmall) }
            } } }
            item { AttachmentGallery(entry.attachments, vm.media) }
            item { TextButton(onClick = { deleting = entry }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) } }
        }
    }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.diary_delete_note), { deleting = null }) { deleting = null; vm.delete(value) } }
}
