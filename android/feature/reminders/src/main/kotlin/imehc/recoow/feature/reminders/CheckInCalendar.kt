package imehc.recoow.feature.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.domain.reminders.Reminder
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CheckInCalendar(r: Reminder?, state: RemindersState, vm: RemindersViewModel, onBack: () -> Unit) {
    val today = Instant.ofEpochMilli(state.now).atZone(state.zone).toLocalDate()
    var monthKey by rememberSaveable(r?.id) { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthKey)
    var selected by rememberSaveable(r?.id) { mutableStateOf(arrayListOf<String>()) }
    var note by rememberSaveable(r?.id) { mutableStateOf("") }
    val allowed = selected.map(LocalDate::parse).filter { r?.canMakeUp(it, state.now, state.zone) == true }.toSet()
    val completions = r?.completions?.associateBy { it.date }.orEmpty()
    val weekdays = LocalResources.current.getStringArray(R.array.reminder_weekday_names)
    val dateFormat = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
        .withLocale(LocalConfiguration.current.locales[0])
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.reminder_make_up)) }, navigationIcon = { BackButton(onBack) }, actions = {
        TextButton(enabled = r != null && allowed.isNotEmpty() && !state.busy, onClick = { vm.saveMakeup(requireNotNull(r).id, allowed, note) }, modifier = Modifier.testTag("makeup_save")) { Text(stringResource(R.string.reminder_save)) }
    }) }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(RecoowLayout.sectionGap)) {
        if (r == null) item { Text(stringResource(R.string.reminder_missing)) }
        else {
            item { Text(stringResource(R.string.reminder_makeup_hint)); Text(stringResource(R.string.reminder_calendar_legend), style = MaterialTheme.typography.bodySmall) }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { monthKey = month.minusMonths(1).toString() }, enabled = month > YearMonth.from(r.start(state.zone))) { Text(stringResource(R.string.reminder_previous_month)) }
                Text(month.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { monthKey = month.plusMonths(1).toString() }, enabled = month < YearMonth.from(today)) { Text(stringResource(R.string.reminder_next_month)) }
            } }
            item { Row { weekdays.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelSmall) } } } }
            val offset = month.atDay(1).dayOfWeek.value % 7
            val cells = List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week -> item { Row {
                (0..6).forEach { index -> val day = week.getOrNull(index)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val checked = day in r.dates
                            val picked = day.toString() in selected
                            val pick = { selected = ArrayList(if (picked) selected - day.toString() else selected + day.toString()) }
                            val label = day.dayOfMonth.toString() + if (completions[day]?.kind == imehc.recoow.core.domain.reminders.CheckInKind.MAKE_UP) " ↺" else if (checked) " ✓" else ""
                            val completion = if (!checked) null else stringResource(if (completions[day]?.kind == imehc.recoow.core.domain.reminders.CheckInKind.MAKE_UP) R.string.reminder_calendar_made_up else R.string.reminder_calendar_checked_in)
                            val dayModifier = Modifier.testTag("makeup_$day").semantics {
                                contentDescription = day.format(dateFormat)
                                this.selected = picked
                                if (completion != null) stateDescription = completion
                            }
                            if (picked) FilledTonalButton(onClick = pick, enabled = !state.busy, contentPadding = PaddingValues(0.dp), modifier = dayModifier) { Text(label) }
                            else TextButton(onClick = pick, enabled = !state.busy && r.canMakeUp(day, state.now, state.zone), contentPadding = PaddingValues(0.dp), modifier = dayModifier) { Text(label) }
                        }
                    }
                }
            } } }
            item { Text(stringResource(R.string.reminder_selected_days, allowed.size)) }
            item { OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.reminder_note)) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) }
        }
    } }
}
