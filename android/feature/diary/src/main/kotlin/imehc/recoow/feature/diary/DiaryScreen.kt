package imehc.recoow.feature.diary

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
    // 恢复的目的地不能重放其链接而丢弃正在编辑的内容。
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) { vm.show(recordId); deliveredRecordId = recordId }
    }
    val nested = draft != null || detail != null || managing || source != null
    val entryDetail = draft == null && !managing && source == null && detail != null
    val back = recordBackAction(state.busy, nested, entryDetail, vm::back, onBack)
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
    val query by vm.query.collectAsStateWithLifecycle()
    val tag by vm.tagFilter.collectAsStateWithLifecycle()
    val date by vm.dateFilter.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.query(""); focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching, closeSearch)
    val moods = LocalResources.current.getStringArray(R.array.diary_moods)
    val zone = ZoneId.systemDefault()
    val tags = state.data.tagReferences()
    val photoLabel = stringResource(R.string.diary_photos)
    val audioLabel = stringResource(R.string.diary_audio)
    val filtered = state.data.entries.filter { entry ->
        (tag == null || entry.tags.any { it.key == tag }) &&
            (date == null || Instant.ofEpochMilli(entry.occurredAt).atZone(zone).toLocalDate().toString() == date) &&
            listOf(entry.title, entry.content, moods[entry.mood.ordinal], state.data.resolvedTags(entry).joinToString { it.value },
                entry.links.joinToString { it.title + " " + it.subtitle.orEmpty() },
                entry.attachments.joinToString { it.title ?: if (it.kind == imehc.recoow.core.domain.media.AttachmentKind.PHOTO) photoLabel else audioLabel })
                .joinToString(" ").contains(query.trim(), ignoreCase = true)
    }
    val summary = state.data.summary(LocalDate.now(), zone)
    RecordPage(stringResource(R.string.diary_title), { if (searching) closeSearch() else back() }, tag = "tool_diary", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true },
        searchTag = "diary_search_toggle", searchQuery = query, onSearchQuery = vm::query,
        topActions = {
            DiaryTagAction(vm::manageTags, enabled = !state.loading && !state.failed && !state.busy)
        },
        add = if (!state.failed && !state.loading) stringResource(R.string.diary_add) else null,
        addTag = "diary_add", onAdd = { vm.edit() }) {
        if (state.loading || state.failed) item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item(key = "summary") {
                RecoowGroup {
                    RecoowMetricStrip(listOf(
                        RecoowMetric(stringResource(R.string.diary_metric_total), summary.total.toString(), icon = CommonR.drawable.ic_tool_diary),
                        RecoowMetric(stringResource(R.string.diary_metric_today), summary.today.toString(), icon = CommonR.drawable.ic_check),
                        RecoowMetric(stringResource(R.string.diary_metric_days), summary.activeDays.toString(), icon = CommonR.drawable.ic_tool_anniversaries),
                        RecoowMetric(stringResource(R.string.diary_metric_streak), summary.streak.toString(), icon = CommonR.drawable.ic_metric_streak),
                    ))
                }
            }
            item(key = "entries") {
                RecoowGroup {
                    RecordListHeader(stringResource(R.string.diary_entries_count, filtered.size),
                        modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal), action = {
                            TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); filters = true }, modifier = Modifier.testTag("diary_filters")) {
                                Icon(painterResource(CommonR.drawable.ic_filter), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.diary_filter))
                            }
                        })
                    if (tag != null || date != null) FlowRow(Modifier.padding(horizontal = RecoowLayout.rowHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tag != null) FilterChip(true, onClick = { vm.filterTag(null) },
                            label = { Text(tags.firstOrNull { it.key == tag }?.value ?: tag.orEmpty()) },
                            trailingIcon = { Icon(painterResource(CommonR.drawable.ic_close), stringResource(CommonR.string.record_remove), Modifier.size(14.dp)) })
                        if (date != null) FilterChip(true, onClick = { vm.filterDate(null) }, label = { Text(date.orEmpty()) },
                            trailingIcon = { Icon(painterResource(CommonR.drawable.ic_close), stringResource(CommonR.string.record_remove), Modifier.size(14.dp)) })
                    }
                    RecoowDivider(inset = 0.dp)
                    if (filtered.isEmpty()) {
                        val relaxed = state.data.entries.isNotEmpty()
                        RecoowEmptyState(
                            title = stringResource(if (relaxed) CommonR.string.record_no_matches else CommonR.string.record_empty),
                            actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                            onAction = if (relaxed) ({ closeSearch(); vm.filterTag(null); vm.filterDate(null) }) else null,
                        )
                    } else filtered.forEachIndexed { index, entry ->
                        DiaryRow(entry, moods[entry.mood.ordinal], state, vm)
                        if (index < filtered.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                    }
                }
            }
        }
    }
    if (filters) DiaryFilterSheet(tags, tag, date, { filters = false }) { selectedTag, selectedDate ->
        vm.filterTag(selectedTag); vm.filterDate(selectedDate); filters = false
    }
}

/** 标签、日期共用一个筛选面板，应用前保留草稿，关闭不改变当前结果。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DiaryFilterSheet(tags: List<DiaryTagReference>, tag: String?, date: String?,
    onDismiss: () -> Unit, onApply: (String?, String?) -> Unit) {
    var selectedTag by rememberSaveable { mutableStateOf(tag) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = date?.let(LocalDate::parse)
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    RecoowBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = RecoowLayout.pagePadding)) {
            RecoowSheetHeader(stringResource(R.string.diary_filter_title))
            Text(stringResource(R.string.diary_tags), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedTag == null, { selectedTag = null }, label = { Text(stringResource(CommonR.string.record_all)) })
                tags.forEach { ref -> FilterChip(selectedTag == ref.key, { selectedTag = ref.key }, label = { Text(ref.value) }) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diary_filter_date_label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                FilterChip(dateState.selectedDateMillis == null, { dateState.selectedDateMillis = null },
                    label = { Text(stringResource(R.string.diary_any_date)) })
            }
            DatePicker(dateState, title = null, headline = null, showModeToggle = false)
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedTag = null; dateState.selectedDateMillis = null }) {
                    Text(stringResource(CommonR.string.record_clear_filters))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(selectedTag, dateState.selectedDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                }) }, modifier = Modifier.testTag("diary_apply_filters")) { Text(stringResource(R.string.diary_apply_filters)) }
            }
        }
    }
}

@Composable private fun DiaryBadges(mood: String, tags: List<DiaryTagReference>,
    tagContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordBadge(mood, containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        tags.forEach { ref ->
            RecordBadge("# " + ref.value, containerColor = tagContainerColor)
        }
    }
}

/** 标题独占一行，日期、正文、心情与标签分层，关联数量不混进标签。 */
@Composable private fun DiaryRow(entry: DiaryEntry, mood: String, state: DiaryState, vm: DiaryViewModel) {
    RecordCoverRow(entry.attachments.firstOrNull { it.kind == imehc.recoow.core.domain.media.AttachmentKind.PHOTO },
        vm.media, CommonR.drawable.ic_tool_diary, RecoowToolIcons.tint(ToolRoute.DIARY), onClick = { vm.show(entry.id) }) {
        Text(entry.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(recordDateTime(entry.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entry.content.isNotBlank()) Text(entry.content, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        DiaryBadges(mood, state.data.resolvedTags(entry))
        if (entry.links.isNotEmpty() || entry.attachments.isNotEmpty()) Text(
            stringResource(R.string.diary_relations_count, entry.links.size, entry.attachments.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
/** 列表与表单共用标签入口，保持图标、颜色、点击范围和可访问性名称一致。 */
@Composable private fun DiaryTagAction(onClick: () -> Unit, enabled: Boolean) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(RecoowLayout.controlSize).testTag("diary_manage_tags")) {
        Icon(painterResource(CommonR.drawable.ic_tag), stringResource(R.string.diary_manage_tags),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(RecoowLayout.iconSize))
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
    var audioRecording by remember { mutableStateOf(false) }
    val emptyError = if (attempted && !d.valid) stringResource(R.string.diary_need_content) else null
    val captureLocation: () -> Unit = {
        locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    RecordPage(stringResource(if (d.revision == null) R.string.diary_add else R.string.diary_edit), back, busy = state.busy, compact = true,
        topActions = { DiaryTagAction(vm::manageTags, enabled = !state.busy) },
        action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check, actionEnabled = !audioRecording, onAction = { if (d.valid && !state.failed && !state.loading) vm.save(defaultTitle) else attempted = true }) {
        item {
            RecordForm {
                RecordFormText(stringResource(R.string.diary_entry_title), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, placeholder = "", error = emptyError, tag = "diary_title")
                RecordFormDivider()
                RecordFormText(stringResource(R.string.diary_content), d.content, { vm.update(d.copy(content = it)) },
                    enabled = !state.busy, multiline = true, tag = "diary_content")
            }
        }
        item {
            Column {
                RecoowSectionHeader(stringResource(R.string.diary_record_info))
                RecordForm {
                    RecordFormDateTime(stringResource(CommonR.string.record_date), d.occurredAt, { vm.update(d.copy(occurredAt = it)) }, !state.busy)
                    RecordFormDivider()
                    RecordChoice(stringResource(R.string.diary_mood), d.mood, DiaryMood.entries.map { it to moods[it.ordinal] },
                        { vm.update(d.copy(mood = it)) }, enabled = !state.busy, bordered = false)
                    if (available.isNotEmpty()) {
                        RecordFormDivider()
                        Column(Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp)) {
                            Text(stringResource(R.string.diary_tags), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                available.forEach { ref ->
                                    FilterChip(d.tags.any { it.key == ref.key }, {
                                        vm.update(d.copy(tags = if (d.tags.any { it.key == ref.key }) d.tags.filterNot { it.key == ref.key } else d.tags + ref))
                                    }, label = { Text(ref.value) }, enabled = !state.busy)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column {
                RecoowSectionHeader(stringResource(R.string.diary_links), trailing = d.links.size.takeIf { it > 0 }?.toString())
                RecoowGroup {
                    RecoowRow(title = stringResource(R.string.diary_select_links), titleColor = MaterialTheme.colorScheme.primary,
                        icon = { Icon(painterResource(CommonR.drawable.ic_link), null, tint = MaterialTheme.colorScheme.primary) },
                        showChevron = true, onClick = if (!state.busy) ({ vm.selectSource(DiarySource.BILL) }) else null,
                        modifier = Modifier.testTag("diary_select_links"))
                    d.links.forEach { link ->
                        RecoowDivider()
                        DiaryLinkedRecordRow(link) {
                            IconButton(onClick = { vm.update(d.copy(links = d.links.filterNot { it.key == link.key })) }, enabled = !state.busy) {
                                Icon(painterResource(CommonR.drawable.ic_close), stringResource(CommonR.string.record_remove),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        item { AttachmentInput(d.attachments, { vm.update(d.copy(attachments = it)) }, vm.media, vm.processor, state.preferences, sectioned = true, enabled = !state.busy, onRecordingChange = { audioRecording = it }) }
        item {
            val hasLocation = d.latitude != null && d.longitude != null
            Column {
                RecoowSectionHeader(stringResource(R.string.diary_location_label))
                RecoowGroup {
                    RecordMetadataRow(
                        title = if (hasLocation) stringResource(R.string.diary_location, requireNotNull(d.latitude), requireNotNull(d.longitude))
                            else stringResource(if (state.locating) R.string.diary_locating else R.string.diary_capture_location),
                        modifier = Modifier.testTag("diary_location_row"),
                        onClick = if (!hasLocation && !state.busy && !state.locating) captureLocation else null,
                        icon = { Icon(painterResource(CommonR.drawable.ic_location), null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(RecoowLayout.iconSize)) },
                    ) {
                        if (state.locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = {
                            if (hasLocation) vm.update(d.copy(latitude = null, longitude = null, accuracy = null)) else captureLocation()
                        }, enabled = !state.busy, modifier = Modifier.size(RecoowLayout.controlSize)) {
                            Icon(painterResource(if (hasLocation) CommonR.drawable.ic_close else CommonR.drawable.ic_plus),
                                stringResource(if (hasLocation) R.string.diary_remove_location else R.string.diary_capture_location),
                                tint = if (hasLocation) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(RecoowLayout.iconSize))
                        }
                    }
                }
            }
        }
    }
}
private fun diarySourceRoute(source: DiarySource): ToolRoute = when (source) {
    DiarySource.TRACK -> ToolRoute.LOCATION
    DiarySource.BILL -> ToolRoute.BILLS
    DiarySource.REMINDER -> ToolRoute.REMINDERS
    DiarySource.ANNIVERSARY -> ToolRoute.ANNIVERSARIES
    DiarySource.ITEM -> ToolRoute.ITEMS
    DiarySource.CHOICE -> ToolRoute.DECISION
}

/** 与已选关联共用行结构：来源图标、标题、摘要、日期和右侧操作各有固定位置。 */
@Composable private fun DiaryLinkedRecordRow(link: DiaryLink, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null) {
    val source = DiarySource.entries.firstOrNull { it.wire == link.sourceType }
    val resources = LocalResources.current
    val bill = diaryBillDisplay(link, resources)
    val subtitle = if (bill != null) listOfNotNull(diaryBillTypeLabel(bill.type, resources), bill.subtitle).joinToString(" · ")
        else diaryLinkSubtitle(link, resources)
    if (bill != null) {
        RecordLinkedBillRow(link.title, bill.amount, bill.type, modifier, subtitle = subtitle,
            date = link.occurredAt?.let { recordDateTime(it) }, onClick = onClick, trailing = trailing)
        return
    }
    RecordMetadataRow(link.title, modifier, subtitle = subtitle,
        date = link.occurredAt?.let { recordDateTime(it) }, onClick = onClick,
        icon = source?.let { { val route = diarySourceRoute(it)
            Icon(painterResource(RecoowToolIcons.drawable(route)), null, tint = RecoowToolIcons.tint(route), modifier = Modifier.size(RecoowLayout.iconSize))
        } }, trailing = trailing)

}

@Composable private fun DiarySourcePicker(vm: DiaryViewModel, source: DiarySource, d: DiaryDraft, back: () -> Unit) {
    val values by vm.sources.collectAsStateWithLifecycle()
    val loading by vm.sourceLoading.collectAsStateWithLifecycle()
    val failed by vm.sourceFailed.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val names = LocalResources.current.getStringArray(R.array.diary_sources)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; query = ""; focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching, closeSearch)
    val resources = LocalResources.current
    val filtered = values.filter {
        listOf(it.title, diaryLinkSubtitle(it, resources).orEmpty(), it.subtitle.orEmpty(),
            diaryBillDisplay(it, resources)?.let { bill -> diaryBillTypeLabel(bill.type, resources) }.orEmpty())
            .joinToString(" ").contains(query.trim(), true)
    }
    RecordPage(stringResource(R.string.diary_select_links), { if (searching) closeSearch() else back() }, tag = "diary_source_picker", compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true },
        searchTag = "diary_source_search", searchQuery = query, onSearchQuery = { query = it },
        action = stringResource(CommonR.string.record_confirm), actionIcon = CommonR.drawable.ic_check,
        onAction = { focus.clearFocus(); keyboard?.hide(); back() }) {
        item {
            RecoowGroup {
                RecordChoice(stringResource(R.string.diary_source), source, DiarySource.entries.map { it to names[it.ordinal] },
                    vm::selectSource, bordered = false)
            }
        }
        item {
            RecoowGroup {
                RecordListHeader(names[source.ordinal], modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal),
                    trailing = stringResource(R.string.diary_selected_count, d.links.size))
                RecoowDivider(inset = 0.dp)
                when {
                        loading || failed -> RecordLoadState(loading, failed) { vm.selectSource(source) }
                        filtered.isEmpty() -> RecoowEmptyState(
                            title = stringResource(if (values.isEmpty()) R.string.diary_no_link_records else CommonR.string.record_no_matches),
                            actionLabel = if (query.isNotBlank()) stringResource(CommonR.string.record_clear_filters) else null,
                            onAction = if (query.isNotBlank()) closeSearch else null,
                        )
                        else -> filtered.forEachIndexed { index, link ->
                            val selected = d.links.any { it.key == link.key }
                            DiaryLinkedRecordRow(link, modifier = Modifier.recordToggleState(selected), onClick = {
                                vm.update(d.copy(links = if (selected) d.links.filterNot { it.key == link.key } else d.links + link))
                            }) { Checkbox(selected, null, modifier = Modifier.size(RecoowLayout.controlSize)) }
                            if (index < filtered.lastIndex) RecoowDivider()
                        }
                }
            }
        }
    }
}
@Composable private fun DiaryDetail(vm: DiaryViewModel, state: DiaryState, entry: DiaryEntry?, back: () -> Unit, onSource: (ToolRoute, String) -> Unit) {
    var deleting by remember { mutableStateOf<DiaryEntry?>(null) }
    val moods = LocalResources.current.getStringArray(R.array.diary_moods)
    RecordPage(entry?.title ?: stringResource(R.string.diary_title), back, tag = "diary_detail", busy = state.busy,
        more = if (entry != null) listOf(
            RecoowAction(stringResource(CommonR.string.record_edit), enabled = !state.busy, onClick = { vm.edit(entry) }),
            RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy, onClick = { deleting = entry }),
        ) else emptyList()) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(recordDateTime(entry.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DiaryBadges(moods[entry.mood.ordinal], state.data.resolvedTags(entry),
                        tagContainerColor = MaterialTheme.colorScheme.surface)
                }
            }
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
                            val action: (() -> Unit)? = if (route != null) ({ onSource(route, link.sourceId) }) else null
                            DiaryLinkedRecordRow(link, onClick = action) { if (action != null) RecoowChevron() }
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
