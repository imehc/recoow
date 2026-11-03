package imehc.recoow.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, onRecord: (ToolRoute, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle(); val search by vm.search.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle(); val route by vm.route.collectAsStateWithLifecycle(); val anchor by vm.week.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    var picker by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filterPanel by rememberSaveable { mutableStateOf(false) }
    RecordRefreshEffect { vm.environment(locale = locale) }
    val week = LocalDate.parse(anchor).with(WeekFields.of(locale).dayOfWeek(), 1)
    val back = { if (!state.busy) { if (state.selecting) vm.selecting() else onBack() } }
    BackHandler(state.selecting || state.busy, back)
    RecordPage(stringResource(R.string.history_title), back, "history_page", state.busy,
        action = stringResource(if (state.selecting) R.string.history_done else R.string.history_select), onAction = vm::selecting,
        more = listOf(
            RecoowAction(stringResource(R.string.history_filter_tool), enabled = !state.busy, onClick = { filterPanel = !filterPanel }),
            RecoowAction(stringResource(R.string.history_filter_date), enabled = !state.busy, onClick = { picker = true }),
            RecoowAction(stringResource(R.string.history_today), enabled = !state.busy, onClick = { vm.date(LocalDate.now()) }),
        ),
        searching = searching, onSearchToggle = { searching = !searching }, searchTag = "history_search_toggle") {
        if (searching) item { RecordSearchField(search, vm::search, tag = "history_search") }
        val activeRoute = route; val activeDate = date
        if (activeRoute != null || activeDate != null) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeRoute != null) FilterChip(true, { vm.route(null) }, label = { Text(names[activeRoute.ordinal]) })
                if (activeDate != null) FilterChip(true, { vm.date(null) }, label = { Text(activeDate) })
            }
        }
        if (filterPanel) item {
            RecoowGroup {
                RecordChoice(stringResource(R.string.history_tool), route,
                    listOf(null to stringResource(R.string.history_all)) + ToolRoute.entries.map { it to names[it.ordinal] }, vm::route, !state.busy, bordered = false)
                RecoowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.week(-1) }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.history_previous_week)) }
                    TextButton(onClick = { vm.week(1) }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.history_next_week)) }
                    Spacer(Modifier.weight(1f))
                    Text("$week — ${week.plusDays(6)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                }
                RecoowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.route(null); vm.date(null); filterPanel = false }) { Text(stringResource(CommonR.string.record_clear_filters)) }
                }
            }
        }
        if (state.countsFailed) item { TextButton(onClick = vm::retry) { Text(stringResource(R.string.history_calendar_retry)) } }
        if (state.selecting) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::selectLoaded, enabled = !state.busy) { Text(stringResource(R.string.history_select_loaded)) }
            FilledTonalButton(onClick = { vm.prepare() }, enabled = state.selected.isNotEmpty() && !state.busy) { Text(stringResource(R.string.history_delete_selected, state.selected.size)) }
        } }
        if (state.entries.isEmpty() && !state.loading && !state.failed) item {
            val relaxed = date != null || route != null || search.isNotBlank()
            RecoowGroup {
                RecoowEmptyState(
                    title = stringResource(if (relaxed) R.string.history_no_matches else R.string.history_empty),
                    actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                    onAction = if (relaxed) ({ vm.search(""); vm.route(null); vm.date(null) }) else null,
                )
            }
        }
        items(state.entries, key = { it.key }) { entry ->
            // 删除收进长按菜单：每行不再为它留一整行。
            var menu by remember { mutableStateOf(false) }
            Box {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().testTag("history_${entry.key}")
                        .combinedClickable(
                            enabled = !state.busy,
                            onClick = { if (state.selecting) vm.select(entry) else onRecord(entry.route, entry.recordId) },
                            onLongClick = { if (!state.selecting) menu = true },
                        ).then(if (state.selecting) Modifier.recordToggleState(entry.key in state.selected) else Modifier)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.selecting) Checkbox(entry.key in state.selected, null)
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(recordDateTime(entry.timestamp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val subtitle = buildList {
                            add(names[entry.route.ordinal])
                            if (entry.route == ToolRoute.FOOD) add(stringResource(R.string.history_food_day, entry.day.toString(), entry.count))
                        }.joinToString(" · ")
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entry.detail.isNotBlank()) Text(entry.detail, maxLines = 2, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_select_many)) }, onClick = { menu = false; vm.selecting() })
                    DropdownMenuItem(text = { Text(stringResource(CommonR.string.record_delete)) },
                        onClick = { menu = false; vm.prepare(listOf(entry)) })
                }
            }
        }
        item {
            RecordListFooter(
                loading = state.loading,
                failed = state.failed,
                hasMore = state.next != null && !state.loading && !state.failed,
                endLabel = if (state.entries.isNotEmpty() && state.next == null && !state.loading && !state.failed) stringResource(CommonR.string.record_end) else null,
                onRetry = vm::retry,
                onMore = vm::more,
            )
        }
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
