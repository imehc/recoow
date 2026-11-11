package imehc.recoow.feature.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
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
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(stringResource(R.string.reminder_make_up), navigationIcon = { BackButton(onBack) }, action = {
            IconButton(enabled = r != null && allowed.isNotEmpty() && !state.busy,
                onClick = { vm.saveMakeup(requireNotNull(r).id, allowed, note) }, modifier = Modifier.testTag("makeup_save")) {
                Icon(painterResource(CommonR.drawable.ic_check), stringResource(R.string.reminder_save), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (r == null) item { RecoowGroup { RecoowEmptyState(stringResource(R.string.reminder_missing)) } }
            else {
                item { RecoowNotice(stringResource(R.string.reminder_makeup_hint)) }
                item(key = "calendar") {
                    RecoowGroup {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { monthKey = month.minusMonths(1).toString() }, enabled = month > YearMonth.from(r.start(state.zone))) {
                                Icon(painterResource(CommonR.drawable.ic_record_back), stringResource(R.string.reminder_previous_month), modifier = Modifier.size(20.dp))
                            }
                            val locale = LocalConfiguration.current.locales[0]
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(month.format(java.time.format.DateTimeFormatter.ofPattern(if (locale.language == "zh") "yyyy年M月" else "MMM yyyy", locale)),
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                            IconButton(onClick = { monthKey = month.plusMonths(1).toString() }, enabled = month < YearMonth.from(today)) {
                                Icon(painterResource(CommonR.drawable.ic_chevron_right), stringResource(R.string.reminder_next_month), modifier = Modifier.size(20.dp))
                            }
                        }
                        RecoowDivider(inset = 0.dp)
                        Row(Modifier.padding(vertical = 8.dp)) {
                            weekdays.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                        val offset = month.atDay(1).dayOfWeek.value % 7
                        val cells = List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
                        cells.chunked(7).forEach { week -> Row {
                            (0..6).forEach { index ->
                                val day = week.getOrNull(index)
                                Box(Modifier.weight(1f).heightIn(min = RecoowLayout.controlSize), contentAlignment = Alignment.Center) {
                                    if (day != null) {
                                        val checked = day in r.dates
                                        val madeUp = completions[day]?.kind == imehc.recoow.core.domain.reminders.CheckInKind.MAKE_UP
                                        val picked = day.toString() in selected
                                        val pick = { selected = ArrayList(if (picked) selected - day.toString() else selected + day.toString()) }
                                        val completion = if (!checked) null else stringResource(if (madeUp) R.string.reminder_calendar_made_up else R.string.reminder_calendar_checked_in)
                                        val dayModifier = Modifier.fillMaxWidth().heightIn(min = RecoowLayout.controlSize).testTag("makeup_$day").semantics {
                                            contentDescription = day.format(dateFormat)
                                            this.selected = picked
                                            if (completion != null) stateDescription = completion
                                        }
                                        val label: @Composable RowScope.() -> Unit = {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyLarge)
                                                if (checked) Icon(painterResource(if (madeUp) CommonR.drawable.ic_clock else CommonR.drawable.ic_check),
                                                    null, modifier = Modifier.size(10.dp))
                                            }
                                        }
                                        if (picked) FilledTonalButton(onClick = pick, enabled = !state.busy, contentPadding = PaddingValues(0.dp),
                                            modifier = dayModifier, content = label)
                                        else TextButton(onClick = pick, enabled = !state.busy && r.canMakeUp(day, state.now, state.zone),
                                            contentPadding = PaddingValues(0.dp), modifier = dayModifier,
                                            colors = ButtonDefaults.textButtonColors(disabledContentColor = if (checked) {
                                                if (madeUp) RecoowPalette.warningColor(MaterialTheme.colorScheme.background.luminance() < 0.5f)
                                                else MaterialTheme.colorScheme.tertiary
                                            } else MaterialTheme.colorScheme.onSurfaceVariant), content = label)
                                    }
                                }
                            }
                        } }
                        Row(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(CommonR.drawable.ic_check), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.reminder_calendar_checked_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Icon(painterResource(CommonR.drawable.ic_clock), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.reminder_calendar_made_up), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    RecordFormSection(stringResource(R.string.reminder_selected_days, allowed.size)) {
                        RecordFormText(stringResource(R.string.reminder_note), note, { note = it }, enabled = !state.busy,
                            multiline = true, placeholder = "")
                    }
                }
            }
        }
    }
}
