package imehc.recoow.feature.reminders

import imehc.recoow.core.designsystem.recordControlDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.reminder_back)) }
}

@Composable internal fun <T> ChoiceField(label: String, selected: T, options: List<Pair<T, String>>, onChange: (T) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.reminder_choose)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().recordControlDescription(label, selectedLabel)) {
                Text(selectedLabel)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(value) }) }
            }
        }
    }
}

@Composable internal fun dateTime(value: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(value, zone, locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(value).atZone(zone)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateTimeField(label: String, value: Long?, onChange: (Long) -> Unit, enabled: Boolean = true) {
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var pendingDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val zone = ZoneId.systemDefault()
    val initial = remember(value, dateOpen) { Instant.ofEpochMilli(value ?: System.currentTimeMillis()).atZone(zone) }
    OutlinedButton(onClick = { dateOpen = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column { Text(label, style = MaterialTheme.typography.labelLarge); Text(value?.let { dateTime(it) } ?: stringResource(R.string.reminder_choose_date)) }
    }
    if (dateOpen) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.toLocalDate().takeIf { it.year in 1900..2100 }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(), yearRange = 1900..2100)
        DatePickerDialog(onDismissRequest = { dateOpen = false }, confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = {
                pendingDay = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }; dateOpen = false
            }) { Text(stringResource(R.string.reminder_confirm)) }
        }, dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.reminder_cancel)) } }) { DatePicker(state) }
    }
    pendingDay?.let { day ->
        val time = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { pendingDay = null }, title = { Text(label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimeInput(time) } },
            confirmButton = { TextButton(onClick = { onChange(LocalDate.ofEpochDay(day).atTime(time.hour, time.minute).atZone(zone).toInstant().toEpochMilli()); pendingDay = null }) { Text(stringResource(R.string.reminder_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingDay = null }) { Text(stringResource(R.string.reminder_cancel)) } })
    }
}
