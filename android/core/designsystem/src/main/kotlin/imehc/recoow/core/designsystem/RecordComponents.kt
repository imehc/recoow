package imehc.recoow.core.designsystem

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.records.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable fun RecordPage(title: String, onBack: () -> Unit, tag: String = "", busy: Boolean = false,
    action: String? = null, actionEnabled: Boolean = true, onAction: () -> Unit = {},
    add: String? = null, addTag: String? = null, onAdd: () -> Unit = {},
    more: List<RecoowAction> = emptyList(),
    searching: Boolean = false, onSearchToggle: (() -> Unit)? = null, searchTag: String? = null,
    searchQuery: String? = null, onSearchQuery: (String) -> Unit = {},
    actionIcon: Int? = null, topActions: (@Composable RowScope.() -> Unit)? = null,
    compact: Boolean = false,
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit) {
    Scaffold(Modifier.testTag(tag), containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(title = title, navigationIcon = {
            IconButton(onClick = onBack, enabled = !busy) { Icon(painterResource(R.drawable.ic_record_back), stringResource(R.string.record_back)) }
        }, action = {
            if (onSearchToggle != null) RecoowSearchToggle(searching, onSearchToggle, tag = searchTag)
            topActions?.invoke(this)
            RecoowOverflowMenu(more)
            if (action != null) {
                if (actionIcon != null) IconButton(onClick = onAction, enabled = actionEnabled && !busy) {
                    Icon(painterResource(actionIcon), action, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else TextButton(onClick = onAction, enabled = actionEnabled && !busy) { Text(action) }
            }
        }, titleContent = if (searchQuery != null) ({ RecordSearchTitle(title, searching, searchQuery, onSearchQuery, tag = searchTag.orEmpty() + "_field") }) else null)
    }, bottomBar = { bottomBar?.invoke() }, floatingActionButton = {
        if (add != null) RecoowFloatingAddAction(add, onClick = onAdd, enabled = !busy, tag = addTag)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (add != null) 96.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) RecoowLayout.compactSectionGap else RecoowLayout.sectionGap)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }; content()
        }
    }
}

/** 列表筛选 / 计数标题行；48dp 操作区域自身承担留白，不再叠加底部间距。 */
@Composable fun RecordListHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke(this)
    }
}

/** 日记心情 / 标签和饮食餐别 / 份量共用的小标记。 */
@Composable fun RecordBadge(label: String, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = containerColor, contentColor = contentColor) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** 有多层元信息的记录行，关联候选和已选记录共用：图标、文字、数值、操作各占固定位置。 */
@Composable fun RecordMetadataRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, date: String? = null,
    icon: (@Composable () -> Unit)? = null, value: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = RecoowLayout.rowMinHeight)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = RecoowLayout.rowHorizontal, vertical = RecoowLayout.rowVertical),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(RecoowLayout.iconBox), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(RecoowLayout.iconTextGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (value != null) { Spacer(Modifier.width(RecoowLayout.iconTextGap)); value() }
        if (trailing != null) {
            if (value == null) Spacer(Modifier.width(RecoowLayout.iconTextGap))
            Row(Modifier.size(RecoowLayout.controlSize), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** 从顶栏右侧展开搜索；输入与标题共用空间，列表位置保持不变。 */
@Composable fun RecordSearchTitle(title: String, searching: Boolean, value: String, onChange: (String) -> Unit, tag: String = "") {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(!searching, enter = fadeIn(tween(180)), exit = fadeOut(tween(100))) {
            Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(searching,
            enter = expandHorizontally(tween(220), expandFrom = Alignment.End) + fadeIn(tween(180)),
            exit = shrinkHorizontally(tween(180), shrinkTowards = Alignment.End) + fadeOut(tween(100))) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp).focusRequester(focus).testTag(tag),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { input -> Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(stringResource(R.string.record_search), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        input()
                    } })
            }
        }
    }
}

/** 顶栏搜索展开后的输入行：自动聚焦、单行、与卡片同圆角；收起后不占版面。 */
@Composable fun RecordSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, tag: String = "") {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().focusRequester(focus).testTag(tag),
        placeholder = { Text(stringResource(R.string.record_search)) }, singleLine = true, shape = MaterialTheme.shapes.medium)
}
/**
 * 文本字段。`error` 非空时描边染红并在下方给出一行具体原因，
 * 用它代替「保存按钮灰着但不解释为什么」。
 */
@Composable fun RecordField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true, multiline: Boolean = false, tag: String = "", error: String? = null) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), label = { Text(label) }, enabled = enabled,
        isError = error != null, shape = MaterialTheme.shapes.medium, singleLine = !multiline, minLines = if (multiline) 3 else 1,
        supportingText = error?.let { { Text(it) } })
}
/**
 * 行式选择器：标签在左、当前值在右、末尾箭头；与设置页的取值行同一套读法。
 * `bordered = false` 用于嵌进 `RecoowGroup` 的分组内，避免卡片套卡片。
 */
@Composable fun <T> RecordChoice(label: String, selected: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, enabled: Boolean = true, bordered: Boolean = true, error: String? = null, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.record_choose)
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = RecoowLayout.rowHorizontal),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedLabel, modifier = Modifier.widthIn(max = 220.dp), style = MaterialTheme.typography.bodyLarge,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                        tint = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp))
                }
                DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp),
                    shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    options.forEachIndexed { index, (value, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChange(value) },
                            modifier = Modifier.testTag("record_option_$index").semantics { this.selected = value == selected })
                    }
                }
            }
        }
    }
    Column(modifier) {
        Box {
            if (bordered) {
                Surface(onClick = { open = true }, enabled = enabled, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().recordControlDescription(label, selectedLabel)) { content() }
            } else {
                Box(Modifier.fillMaxWidth()
                    .clickable(enabled = enabled) { open = true }
                    .recordControlDescription(label, selectedLabel)) { content() }
            }
        }
        if (error != null) Text(
            error,
            modifier = Modifier.padding(start = RecoowLayout.rowHorizontal, end = RecoowLayout.rowHorizontal, top = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
@Composable fun recordDateTime(timestamp: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RecordDatePicker(initial: LocalDate, onDismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.takeIf { it.year in 1900..2100 }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(), yearRange = 1900..2100)
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(enabled = state.selectedDateMillis != null,
        onClick = { state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }) { Text(stringResource(R.string.record_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.record_cancel)) } }) { DatePicker(state) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RecordDateTime(label: String, value: Long, onChange: (Long) -> Unit, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<Long?>(null) }
    val zone = ZoneId.systemDefault(); val initial = Instant.ofEpochMilli(value).atZone(zone)
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Column { Text(label); Text(recordDateTime(value)) } }
    if (open) RecordDatePicker(initial.toLocalDate(), { open = false }) { pending = it.toEpochDay(); open = false }
    pending?.let { day ->
        val state = rememberTimePickerState(initial.hour, initial.minute, true)
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(label) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimeInput(state) } },
            confirmButton = { TextButton(onClick = { onChange(LocalDate.ofEpochDay(day).atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli()); pending = null }) { Text(stringResource(R.string.record_confirm)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.record_cancel)) } })
    }
}
@Composable fun RecordProblem(error: Int?, onDismiss: () -> Unit) {
    error?.let { AlertDialog(onDismissRequest = onDismiss, text = { Text(stringResource(it)) }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.record_confirm)) } }) }
}
@Composable fun RecordDeleteConfirmation(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.record_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.record_cancel)) } })
}
@Composable fun RecordLoadState(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    if (loading) RecordSkeleton() else if (failed) Column {
        Text(stringResource(R.string.record_failed))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.record_retry)) }
    }
}
@Composable fun NamedRecordManager(title: String, values: List<NamedRecord>, draft: NamedRecordDraft?, busy: Boolean,
    deletionMessage: String, onBack: () -> Unit, onEdit: (NamedRecordDraft) -> Unit, onUpdate: (NamedRecordDraft) -> Unit,
    onSave: () -> Unit, onDelete: (NamedRecord) -> Unit, compact: Boolean = false, rowIcon: Int? = null,
    emptyDescription: String? = null) {
    var deleting by remember { mutableStateOf<NamedRecord?>(null) }
    var attempted by remember { mutableStateOf(false) }
    val nameError = if (attempted && draft != null && draft.name.isBlank()) stringResource(R.string.record_field_required) else null
    RecordPage(title, onBack, busy = busy, compact = compact, action = if (draft != null) stringResource(R.string.record_save) else null,
        actionIcon = R.drawable.ic_check,
        onAction = { if (draft?.name?.isNotBlank() == true) onSave() else attempted = true },
        add = if (draft == null) stringResource(R.string.record_add) else null, onAdd = { onEdit(NamedRecordDraft()) }) {
        if (draft != null) {
            item {
                RecordForm {
                    RecordFormText(stringResource(R.string.record_name), draft.name, { onUpdate(draft.copy(name = it)) }, enabled = !busy, placeholder = "", error = nameError)
                    RecordFormDivider()
                    RecordFormText(stringResource(R.string.record_note), draft.note, { onUpdate(draft.copy(note = it)) }, enabled = !busy, multiline = true)
                }
            }
        } else {
            if (values.isEmpty()) item {
                if (emptyDescription == null) Text(stringResource(R.string.record_empty))
                else RecoowGroup { RecoowEmptyState(stringResource(R.string.record_empty), description = emptyDescription) }
            }
            if (values.isNotEmpty()) item {
                RecoowGroup {
                    values.forEachIndexed { index, value ->
                        var menu by remember(value.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { menu = true }
                            .padding(start = RecoowLayout.rowHorizontal, end = 4.dp).heightIn(min = RecoowLayout.rowMinHeight),
                            verticalAlignment = Alignment.CenterVertically) {
                            rowIcon?.let {
                                Box(Modifier.size(RecoowLayout.iconBox), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(it), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(RecoowLayout.iconSize))
                                }
                                Spacer(Modifier.width(RecoowLayout.iconTextGap))
                            }
                            Column(Modifier.weight(1f).padding(vertical = RecoowLayout.rowVertical)) {
                                Text(value.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                value.note?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !busy) {
                                    Icon(painterResource(R.drawable.ic_more), stringResource(R.string.record_more),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                                DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium,
                                    containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.record_edit)) },
                                        onClick = { menu = false; onEdit(NamedRecordDraft.from(value)) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.record_delete)) },
                                        onClick = { menu = false; deleting = value })
                                }
                            }
                        }
                        if (index < values.lastIndex) RecoowDivider(inset = if (rowIcon != null) RecoowLayout.dividerInset else RecoowLayout.rowHorizontal)
                    }
                }
            }
        }
    }
    deleting?.let { value -> RecordDeleteConfirmation(value.name, deletionMessage, { deleting = null }) { deleting = null; onDelete(value) } }
}
