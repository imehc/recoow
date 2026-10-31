package imehc.recoow.feature.anniversaries

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import imehc.recoow.core.calendar.*
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.calendar.DateCalendar
import java.time.*
import kotlinx.coroutines.delay

@Composable fun AnniversariesScreen(vm: AnniversariesViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle(); val detail by vm.detail.collectAsStateWithLifecycle()
    var delivered by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) { if (recordId != null && delivered != recordId) { vm.show(recordId); delivered = recordId } }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { clock = System.currentTimeMillis(); delay(1000) } } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshNotifications() }
    val back: () -> Unit = { if (!state.busy) { if (draft != null || detail != null) vm.back() else onBack() } }
    BackHandler(draft != null || detail != null || state.busy, back)
    when {
        draft != null -> AnniversaryEditor(vm, state, requireNotNull(draft), back)
        detail != null -> AnniversaryDetail(vm, state, state.records.firstOrNull { it.id == detail }, clock, back)
        else -> AnniversaryList(vm, state, clock, back)
    }
    RecordProblem(state.error, vm::clearError)
}
@Composable private fun AnniversaryList(vm: AnniversariesViewModel, state: AnniversaryState, clock: Long, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle(); val zone = ZoneId.systemDefault(); val names = LocalResources.current.getStringArray(R.array.ann_categories)
    val rows = state.records.filter { (it.title + " " + it.note.orEmpty() + " " + names[it.category.ordinal]).contains(query.trim(), true) }
    val next = rows.associate { it.id to it.next(clock, zone, vm.calendar) }
    RecordPage(stringResource(R.string.ann_title), back, tag = "tool_anniversaries", busy = state.busy,
        add = if (!state.loading && !state.failed) stringResource(R.string.ann_add) else null, onAdd = { vm.edit() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item { AnniversaryNotificationControls(vm) }
            item { RecordField(stringResource(CommonR.string.record_search), query, vm::query) }
            if (rows.isEmpty()) item { Text(stringResource(if (state.records.isEmpty()) CommonR.string.record_empty else CommonR.string.record_no_matches)) }
            for (past in listOf(false, true)) {
                val group = rows.filter { (next[it.id] == null) == past }.let { if (past) it.sortedByDescending { r -> r.occurredAt } else it.sortedBy { r -> next[r.id] } }
                if (group.isNotEmpty()) item { Text(stringResource(if (past) R.string.ann_past else R.string.ann_upcoming), style = MaterialTheme.typography.titleMedium) }
                items(group, key = { it.id }) { record -> Card(Modifier.fillMaxWidth().clickable { vm.show(record.id) }) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.title, style = MaterialTheme.typography.titleMedium); Text(names[record.category.ordinal])
                    Text(record.daysUntil(clock, zone, vm.calendar)?.let { stringResource(R.string.ann_days_until, it) } ?: stringResource(R.string.ann_days_since, record.daysSince(clock, zone)))
                    Text(anniversaryDate(record.occurredAt, record.calendar), style = MaterialTheme.typography.bodySmall)
                } } }
            }
        }
    }
}
@Composable private fun AnniversaryDetail(vm: AnniversariesViewModel, state: AnniversaryState, record: Anniversary?, clock: Long, back: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }; val zone = ZoneId.systemDefault(); val names = LocalResources.current.getStringArray(R.array.ann_categories)
    RecordPage(record?.title ?: stringResource(R.string.ann_title), back, tag = "anniversary_detail", busy = state.busy,
        action = record?.let { stringResource(CommonR.string.record_edit) }, onAction = { record?.let(vm::edit) }) {
        if (record == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item { Text(names[record.category.ordinal], style = MaterialTheme.typography.titleMedium); Text(anniversaryDate(record.occurredAt, record.calendar)) }
            item { Text(stringResource(R.string.ann_days_since, record.daysSince(clock, zone)))
                Text(stringResource(R.string.ann_years, vm.calendar.yearsBetween(Instant.ofEpochMilli(record.occurredAt), Instant.ofEpochMilli(clock), record.calendar, zone))) }
            record.next(clock, zone, vm.calendar)?.let { time -> item { Text(stringResource(R.string.ann_next), style = MaterialTheme.typography.titleMedium)
                Text(anniversaryDate(time, record.calendar)); Text(recordDateTime(time)); Text(stringResource(R.string.ann_days_until, record.daysUntil(clock, zone, vm.calendar)!!)) } }
            item { Text(stringResource(if (record.yearly) R.string.ann_yearly else R.string.ann_once)); Text(stringResource(if (record.enabled) R.string.ann_notifications_on else R.string.ann_notifications_off))
                Text(stringResource(R.string.ann_lead_value, record.lead)) }
            item { AnniversaryNotificationControls(vm) }
            record.note?.let { item { Text(it) } }
            item { TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) } }
        }
    }
    if (deleting && record != null) RecordDeleteConfirmation(record.title, stringResource(R.string.ann_delete_note), { deleting = false }) { deleting = false; vm.delete(record) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AnniversaryEditor(vm: AnniversariesViewModel, state: AnniversaryState, d: AnniversaryDraft, back: () -> Unit) {
    val names = LocalResources.current.getStringArray(R.array.ann_categories)
    var dateOpen by rememberSaveable { mutableStateOf(false) }; var timeOpen by rememberSaveable { mutableStateOf(false) }; val zone = ZoneId.systemDefault()
    RecordPage(stringResource(if (d.revision == null) R.string.ann_add else R.string.ann_edit), back, busy = state.busy,
        action = stringResource(CommonR.string.record_save), actionEnabled = d.valid && !state.loading && !state.failed, onAction = vm::save) {
        item { RecordField(stringResource(R.string.ann_name), d.title, { vm.update(d.copy(title = it)) }, !state.busy, tag = "anniversary_title") }
        item { RecordChoice(stringResource(R.string.ann_category), d.category, AnniversaryCategory.entries.map { it to names[it.ordinal] }, { vm.update(d.copy(category = it)) }, !state.busy) }
        item { RecordField(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, !state.busy, multiline = true) }
        item { RecordChoice(stringResource(R.string.ann_calendar), d.calendar, listOf(DateCalendar.GREGORIAN to stringResource(R.string.ann_solar), DateCalendar.CHINESE to stringResource(R.string.ann_lunar)), { vm.update(d.copy(calendar = it)) }, !state.busy) }
        item { OutlinedButton(onClick = { dateOpen = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(anniversaryDate(d.occurredAt, d.calendar)) } }
        item { AnniversaryToggle(stringResource(R.string.ann_yearly), d.yearly, !state.busy) { vm.update(d.copy(yearly = it)) } }
        item { OutlinedButton(onClick = { timeOpen = true }, enabled = !state.busy) { Text(stringResource(R.string.ann_time, d.minutes / 60, d.minutes % 60)) } }
        item { RecordChoice(stringResource(R.string.ann_lead), d.lead, imehc.recoow.core.domain.reminders.leadMinutes.map { it to stringResource(R.string.ann_lead_value, it) }, { vm.update(d.copy(lead = it)) }, !state.busy) }
        item { AnniversaryToggle(stringResource(R.string.ann_enabled), d.enabled, !state.busy) { vm.update(d.copy(enabled = it)) }; AnniversaryNotificationControls(vm) }
    }
    val onDate: (LocalDate) -> Unit = { day -> vm.update(d.copy(occurredAt = day.atTime(Instant.ofEpochMilli(d.occurredAt).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli())); dateOpen = false }
    if (dateOpen) { if (d.calendar == DateCalendar.CHINESE) LunarPicker(d.occurredAt, { dateOpen = false }, onDate)
        else RecordDatePicker(Instant.ofEpochMilli(d.occurredAt).atZone(zone).toLocalDate(), { dateOpen = false }, onDate) }
    if (timeOpen) {
        val time = rememberTimePickerState(d.minutes / 60, d.minutes % 60, true)
        AlertDialog(onDismissRequest = { timeOpen = false }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimeInput(time) } },
            confirmButton = { TextButton(onClick = { vm.update(d.copy(minutes = time.hour * 60 + time.minute)); timeOpen = false }) { Text(stringResource(CommonR.string.record_confirm)) } },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text(stringResource(CommonR.string.record_cancel)) } })
    }
}
@Composable private fun AnniversaryToggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    RecordSwitch(label, value, onChange, enabled = enabled)
}
@Composable private fun anniversaryDate(at: Long, primary: DateCalendar): String {
    val zone = ZoneId.systemDefault(); val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate(); val lunar = LunarDates.from(at, zone)
    val moon = stringResource(R.string.ann_lunar_date, lunar.year, if (lunar.leap) stringResource(R.string.ann_leap) else "", lunar.month, lunar.day)
    val sun = stringResource(R.string.ann_solar_date, date.toString())
    return if (primary == DateCalendar.CHINESE) "$moon\n$sun" else "$sun\n$moon"
}
@Composable private fun LunarPicker(initial: Long, dismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault(); val first = remember { LunarDates.from(initial, zone) }
    var year by rememberSaveable { mutableStateOf(first.year.toString()) }; var month by rememberSaveable { mutableIntStateOf(first.month) }
    var leap by rememberSaveable { mutableStateOf(first.leap) }; var day by rememberSaveable { mutableIntStateOf(first.day) }
    val y = year.toIntOrNull() ?: 0
    val months = remember(y, zone) { if (y in 1900..2100) LunarDates.months(y, zone) else emptyList() }
    val selected = LunarDate(y, month, day, leap); val resolved = remember(selected, zone) { LunarDates.resolve(selected, zone) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.ann_lunar)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecordField(stringResource(R.string.ann_year_range), year, { year = it })
        RecordChoice(stringResource(R.string.ann_month), month to leap, months.map { (it.month to it.leap) to stringResource(R.string.ann_month_value, if (it.leap) stringResource(R.string.ann_leap) else "", it.month) }, { month = it.first; leap = it.second })
        RecordChoice(stringResource(R.string.ann_day), day, (1..30).map { it to it.toString() }, { day = it })
        Text(resolved?.toString() ?: stringResource(R.string.ann_invalid_lunar))
    } }, confirmButton = { TextButton(onClick = { resolved?.let(onDate) }, enabled = resolved != null) { Text(stringResource(CommonR.string.record_confirm)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(CommonR.string.record_cancel)) } })
}
@Composable private fun AnniversaryNotificationControls(vm: AnniversariesViewModel) {
    val state by vm.notificationState.collectAsStateWithLifecycle(); val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshNotifications() }
    if (!state.allowed || !state.exact || state.failed) Column {
        Text(stringResource(if (!state.allowed) R.string.ann_access_off else if (state.failed) R.string.ann_access_failed else R.string.ann_access_inexact))
        FlowRow {
            TextButton(onClick = { if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(stringResource(R.string.ann_access_settings)) }
            if (Build.VERSION.SDK_INT >= 31 && !state.exact) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text(stringResource(R.string.ann_access_exact)) }
        }
    }
}
