package imehc.recoow.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.records.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RecordPage(title: String, onBack: () -> Unit, tag: String = "", busy: Boolean = false,
    action: String? = null, actionEnabled: Boolean = true, onAction: () -> Unit = {},
    add: String? = null, onAdd: () -> Unit = {}, content: LazyListScope.() -> Unit) {
    Scaffold(Modifier.testTag(tag), topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(painterResource(R.drawable.ic_record_back), stringResource(R.string.record_back)) }
    }, actions = { if (action != null) TextButton(onClick = onAction, enabled = actionEnabled && !busy) { Text(action) } }) },
        floatingActionButton = { if (add != null) ExtendedFloatingActionButton(onClick = { if (!busy) onAdd() }) { Text(add) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (add != null) 100.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) { if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }; content() }
    }
}
@Composable fun RecordField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true, multiline: Boolean = false, tag: String = "") {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), label = { Text(label) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 3 else 1)
}
@Composable fun <T> RecordChoice(label: String, selected: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box { OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.record_choose))
        }
            DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                options.forEach { (value, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChange(value) }) }
            }
        }
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
    if (loading) CircularProgressIndicator() else if (failed) Column {
        Text(stringResource(R.string.record_failed))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.record_retry)) }
    }
}
@Composable fun NamedRecordManager(title: String, values: List<NamedRecord>, draft: NamedRecordDraft?, busy: Boolean,
    deletionMessage: String, onBack: () -> Unit, onEdit: (NamedRecordDraft) -> Unit, onUpdate: (NamedRecordDraft) -> Unit,
    onSave: () -> Unit, onDelete: (NamedRecord) -> Unit) {
    var deleting by remember { mutableStateOf<NamedRecord?>(null) }
    RecordPage(title, onBack, busy = busy, action = if (draft != null) stringResource(R.string.record_save) else null,
        actionEnabled = draft?.name?.isNotBlank() == true, onAction = onSave,
        add = if (draft == null) stringResource(R.string.record_add) else null, onAdd = { onEdit(NamedRecordDraft()) }) {
        if (draft != null) {
            item { RecordField(stringResource(R.string.record_name), draft.name, { onUpdate(draft.copy(name = it)) }, !busy) }
            item { RecordField(stringResource(R.string.record_note), draft.note, { onUpdate(draft.copy(note = it)) }, !busy, multiline = true) }
        } else {
            if (values.isEmpty()) item { Text(stringResource(R.string.record_empty)) }
            items(values, key = { it.id }) { value -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(value.name, style = MaterialTheme.typography.titleMedium); value.note?.let { Text(it) }
                FlowRow { TextButton(onClick = { onEdit(NamedRecordDraft.from(value)) }, enabled = !busy) { Text(stringResource(R.string.record_edit)) }
                    TextButton(onClick = { deleting = value }, enabled = !busy) { Text(stringResource(R.string.record_delete)) } }
            } } }
        }
    }
    deleting?.let { value -> RecordDeleteConfirmation(value.name, deletionMessage, { deleting = null }) { deleting = null; onDelete(value) } }
}
