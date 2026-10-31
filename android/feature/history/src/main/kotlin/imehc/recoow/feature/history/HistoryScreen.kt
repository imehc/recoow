package imehc.recoow.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, onRecord: (ToolRoute, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle(); val search by vm.search.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle(); val route by vm.route.collectAsStateWithLifecycle(); val anchor by vm.week.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    var picker by rememberSaveable { mutableStateOf(false) }
    RecordRefreshEffect { vm.environment(locale = locale) }
    val week = LocalDate.parse(anchor).with(WeekFields.of(locale).dayOfWeek(), 1)
    val back = { if (!state.busy) { if (state.selecting) vm.selecting() else onBack() } }
    BackHandler(state.selecting || state.busy, back)
    RecordPage(stringResource(R.string.history_title), back, "history_page", state.busy,
        action = stringResource(if (state.selecting) R.string.history_done else R.string.history_select), onAction = vm::selecting) {
        item { RecordField(stringResource(CommonR.string.record_search), search, vm::search, !state.busy, tag = "history_search") }
        item { RecordChoice(stringResource(R.string.history_tool), route,
            listOf(null to stringResource(R.string.history_all)) + ToolRoute.entries.map { it to names[it.ordinal] }, vm::route, !state.busy) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(date == null, { vm.date(null) }, label = { Text(stringResource(R.string.history_all_dates)) }, enabled = !state.busy)
            OutlinedButton(onClick = { picker = true }, enabled = !state.busy) { Text(date ?: stringResource(R.string.history_choose_date)) }
            TextButton(onClick = { vm.date(LocalDate.now()) }, enabled = !state.busy) { Text(stringResource(R.string.history_today)) }
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.week(-1) }, enabled = !state.busy) { Text(stringResource(R.string.history_previous_week)) }
            TextButton(onClick = { vm.week(1) }, enabled = !state.busy) { Text(stringResource(R.string.history_next_week)) }
            Text("$week — ${week.plusDays(6)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        } }
        item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) { index -> val day = week.plusDays(index.toLong())
                val chosen = date == day.toString()
                val spoken = "$day · " + if (state.countsFailed) stringResource(R.string.history_calendar_retry) else stringResource(R.string.history_count, state.counts[day] ?: 0)
                Surface(onClick = { vm.date(if (chosen) null else day) }, enabled = !state.busy,
                    shape = MaterialTheme.shapes.small,
                    color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.width(48.dp).clearAndSetSemantics {
                        contentDescription = spoken; selected = chosen; role = Role.Button
                        if (!state.busy) onClick { vm.date(if (chosen) null else day); true } else disabled()
                    }) {
                    Column(Modifier.padding(4.dp).heightIn(min = 72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(day.format(DateTimeFormatter.ofPattern("EEE", locale)), style = MaterialTheme.typography.labelSmall)
                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                        Text(if (state.countsFailed) "—" else (state.counts[day] ?: 0).toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } }
        item { Text(stringResource(R.string.history_calendar_scope), style = MaterialTheme.typography.bodySmall) }
        if (state.countsFailed) item { TextButton(onClick = vm::retry) { Text(stringResource(R.string.history_calendar_retry)) } }
        if (state.selecting) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::selectLoaded, enabled = !state.busy) { Text(stringResource(R.string.history_select_loaded)) }
            FilledTonalButton(onClick = { vm.prepare() }, enabled = state.selected.isNotEmpty() && !state.busy) { Text(stringResource(R.string.history_delete_selected, state.selected.size)) }
        } }
        if (state.entries.isEmpty() && !state.loading && !state.failed) item {
            EmptyContent(stringResource(if (state.hasAny || date != null || route != null || search.isNotBlank()) R.string.history_no_matches else R.string.history_empty),
                stringResource(R.string.history_scope))
        }
        items(state.entries, key = { it.key }) { entry ->
            OutlinedCard(onClick = { if (state.selecting) vm.select(entry) else onRecord(entry.route, entry.recordId) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("history_${entry.key}").then(if (state.selecting) Modifier.recordToggleState(entry.key in state.selected) else Modifier)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        if (state.selecting) Checkbox(entry.key in state.selected, null)
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(names[entry.route.ordinal], style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (entry.route == ToolRoute.FOOD) Text(stringResource(R.string.history_food_day, entry.day.toString(), entry.count))
                    Text(recordDateTime(entry.timestamp), style = MaterialTheme.typography.bodySmall)
                    if (entry.detail.isNotBlank()) Text(entry.detail, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                    if (!state.selecting) TextButton(onClick = { vm.prepare(listOf(entry)) }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) }
                }
            }
        }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.failed) item { RecordLoadState(false, true, vm::retry) }
        if (state.next != null && !state.loading) item { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.history_more)) } }
        state.deleteResult?.let { result -> item { Text(stringResource(R.string.history_delete_result, result.deleted.size, result.failed.size)) } }
    }
    if (picker) RecordDatePicker(date?.let(LocalDate::parse) ?: LocalDate.parse(anchor), { picker = false }) { picker = false; vm.date(it) }
    state.plan?.let { plan -> AlertDialog(onDismissRequest = vm::dismiss,
        title = { Text(stringResource(R.string.history_delete_selected, plan.targets.size)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_delete_effects))
            plan.targets.forEach { target -> Text("${names[target.entry.route.ordinal]} · ${target.entry.title}" +
                if (target.entry.route == ToolRoute.FOOD) " · ${stringResource(R.string.history_count, target.entry.count)}" else "") }
        } },
        confirmButton = { TextButton(onClick = vm::delete, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) } },
        dismissButton = { TextButton(onClick = vm::dismiss, enabled = !state.busy) { Text(stringResource(CommonR.string.record_cancel)) } }) }
    if (state.error) RecordProblem(R.string.history_changed, vm::dismiss)
}
