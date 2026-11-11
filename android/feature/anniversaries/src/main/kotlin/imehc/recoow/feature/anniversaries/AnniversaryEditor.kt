package imehc.recoow.feature.anniversaries

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.calendar.*
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.calendar.DateCalendar
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AnniversaryEditor(vm: AnniversariesViewModel, state: AnniversaryState, d: AnniversaryDraft, back: () -> Unit) {
    val names = LocalResources.current.getStringArray(R.array.ann_categories)
    val notifications by vm.notificationState.collectAsStateWithLifecycle()
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && d.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
    RecordPage(stringResource(if (d.revision == null) R.string.ann_add else R.string.ann_edit), back, tag = "anniversary_form", busy = state.busy, compact = true,
        action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check,
        onAction = { if (d.valid && !state.loading && !state.failed) vm.save() else attempted = true }) {
        item(key = "info") {
            RecordFormSection(stringResource(R.string.ann_basic_info)) {
                RecordFormText(stringResource(R.string.ann_name), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, placeholder = "", error = titleError, tag = "anniversary_title")
                RecordFormDivider()
                RecordChoice(stringResource(R.string.ann_category), d.category, AnniversaryCategory.entries.map { it to names[it.ordinal] },
                    { vm.update(d.copy(category = it)) }, enabled = !state.busy, bordered = false)
            }
        }
        item(key = "date") {
            RecordFormSection(stringResource(R.string.ann_date)) {
                RecordChoice(stringResource(R.string.ann_calendar), d.calendar,
                    listOf(DateCalendar.GREGORIAN to stringResource(R.string.ann_solar), DateCalendar.CHINESE to stringResource(R.string.ann_lunar)),
                    { vm.update(d.copy(calendar = it)) }, enabled = !state.busy, bordered = false)
                RecordFormDivider()
                val dates = anniversaryDate(d.occurredAt, d.calendar).lines()
                RecordFormPickerRow(stringResource(CommonR.string.record_date), value = dates.first(),
                    supportingValue = dates.getOrNull(1), enabled = !state.busy, onClick = { dateOpen = true })
                RecordFormDivider()
                RecordSwitch(stringResource(R.string.ann_yearly), d.yearly, { vm.update(d.copy(yearly = it)) },
                    enabled = !state.busy, modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
            }
        }
        item(key = "reminder") {
            RecordFormSection(stringResource(R.string.ann_reminder_section)) {
                RecordFormPickerRow(stringResource(R.string.ann_time_label), value = stringResource(R.string.ann_time_value, d.minutes / 60, d.minutes % 60),
                    enabled = !state.busy, onClick = { timeOpen = true })
                RecordFormDivider()
                RecordChoice(stringResource(R.string.ann_lead), d.lead,
                    imehc.recoow.core.domain.reminders.leadMinutes.map { it to anniversaryLeadLabel(it) },
                    { vm.update(d.copy(lead = it)) }, enabled = !state.busy, bordered = false)
                RecordFormDivider()
                RecordSwitch(stringResource(R.string.ann_enabled), d.enabled, { vm.update(d.copy(enabled = it)) },
                    enabled = !state.busy, modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
            }
        }
        item(key = "note") {
            RecordFormSection(stringResource(CommonR.string.record_note)) {
                RecordFormText(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, enabled = !state.busy,
                    multiline = true, placeholder = "")
            }
        }
        if (attempted && !d.valid && titleError == null) item { RecoowNotice(stringResource(R.string.ann_invalid_form)) }
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!notifications.allowed || !notifications.exact || notifications.failed) item { AnniversaryNotificationControls(vm) }
    }
    val onDate: (LocalDate) -> Unit = { day ->
        vm.update(d.copy(occurredAt = day.atTime(Instant.ofEpochMilli(d.occurredAt).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli()))
        dateOpen = false
    }
    if (dateOpen) {
        if (d.calendar == DateCalendar.CHINESE) LunarPicker(d.occurredAt, { dateOpen = false }, onDate)
        else RecordDatePicker(Instant.ofEpochMilli(d.occurredAt).atZone(zone).toLocalDate(), { dateOpen = false }, onDate)
    }
    if (timeOpen) {
        val time = rememberTimePickerState(d.minutes / 60, d.minutes % 60, true)
        AlertDialog(onDismissRequest = { timeOpen = false }, title = { Text(stringResource(R.string.ann_time_label)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimeInput(time) } },
            confirmButton = { TextButton(onClick = { vm.update(d.copy(minutes = time.hour * 60 + time.minute)); timeOpen = false }) { Text(stringResource(CommonR.string.record_confirm)) } },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text(stringResource(CommonR.string.record_cancel)) } })
    }
}

@Composable private fun LunarPicker(initial: Long, dismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault()
    val first = remember { LunarDates.from(initial, zone) }
    var year by rememberSaveable { mutableStateOf(first.year.toString()) }
    var month by rememberSaveable { mutableIntStateOf(first.month) }
    var leap by rememberSaveable { mutableStateOf(first.leap) }
    var day by rememberSaveable { mutableIntStateOf(first.day) }
    val y = year.toIntOrNull() ?: 0
    val months = remember(y, zone) { if (y in 1900..2100) LunarDates.months(y, zone) else emptyList() }
    val selected = LunarDate(y, month, day, leap)
    val resolved = remember(selected, zone) { LunarDates.resolve(selected, zone) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.ann_lunar)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordForm {
                RecordFormText(stringResource(R.string.ann_year_range), year, { year = it }, keyboard = KeyboardType.Number, placeholder = "")
                RecordFormDivider()
                RecordChoice(stringResource(R.string.ann_month), month to leap,
                    months.map { (it.month to it.leap) to stringResource(R.string.ann_month_value, if (it.leap) stringResource(R.string.ann_leap) else "", it.month) },
                    { month = it.first; leap = it.second }, bordered = false)
                RecordFormDivider()
                RecordChoice(stringResource(R.string.ann_day), day, (1..30).map { it to it.toString() }, { day = it }, bordered = false)
            }
            Text(resolved?.toString() ?: stringResource(R.string.ann_invalid_lunar), style = MaterialTheme.typography.bodyMedium,
                color = if (resolved == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { resolved?.let(onDate) }, enabled = resolved != null) { Text(stringResource(CommonR.string.record_confirm)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(CommonR.string.record_cancel)) } })
}
