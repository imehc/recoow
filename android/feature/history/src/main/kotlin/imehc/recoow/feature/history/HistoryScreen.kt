package imehc.recoow.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, onRecord: (ToolRoute, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val anchor by vm.week.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    var searching by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    RecordRefreshEffect { vm.environment(locale = locale) }
    val week = LocalDate.parse(anchor).with(WeekFields.of(locale).dayOfWeek(), 1)
    val closeSearch = { searching = false; vm.search("") }
    val back = { if (!state.busy) {
        when { state.selecting -> vm.selecting(); searching -> closeSearch(); else -> onBack() }
    } }
    BackHandler(state.selecting || state.busy || searching, back)
    RecordPage(stringResource(R.string.history_title), back, "history_page", state.busy, compact = true,
        topActions = {
            if (state.selecting) {
                IconButton(onClick = { vm.prepare() }, enabled = state.selected.isNotEmpty() && !state.busy,
                    modifier = Modifier.testTag("history_delete_selection")) {
                    Icon(painterResource(CommonR.drawable.ic_delete), stringResource(R.string.history_delete_selected, state.selected.size),
                        tint = if (state.selected.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = vm::selecting, enabled = !state.busy, modifier = Modifier.testTag("history_cancel_selection")) {
                    Icon(painterResource(CommonR.drawable.ic_close), stringResource(R.string.history_cancel_selection),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        more = if (state.selecting) emptyList() else listOf(
            RecoowAction(stringResource(R.string.history_select_many), enabled = !state.busy, onClick = vm::selecting),
            RecoowAction(stringResource(R.string.history_today), enabled = !state.busy) { vm.date(LocalDate.now()) },
        ),
        searching = searching && !state.selecting, searchQuery = search, onSearchQuery = vm::search,
        onSearchToggle = if (state.selecting) null else ({ if (searching) closeSearch() else searching = true }),
        searchTag = "history_search_toggle") {
        item(key = "calendar") {
            RecoowGroup {
                HistoryWeek(week, date, state, vm::week, vm::date)
                Text(stringResource(R.string.history_calendar_count_hint), Modifier.padding(horizontal = RecoowLayout.rowHorizontal).padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "filters") {
            Column {
                RecordListHeader(if (state.selecting) stringResource(R.string.history_selected_count, state.selected.size)
                    else route?.let { names[it.ordinal] } ?: stringResource(R.string.history_all_records),
                    trailing = if (state.selecting) null else stringResource(R.string.history_loaded, state.entries.size), action = {
                        if (state.selecting) TextButton(onClick = vm::selectLoaded, enabled = !state.busy,
                            modifier = Modifier.testTag("history_select_loaded")) { Text(stringResource(R.string.history_select_loaded)) }
                        else {
                            IconButton(onClick = { filters = true }, enabled = !state.busy, modifier = Modifier.testTag("history_filters")) {
                                Icon(painterResource(CommonR.drawable.ic_filter), stringResource(R.string.history_filters),
                                    tint = if (route != null || date != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    })
                if (route != null || date != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (route != null) FilterChip(true, { vm.route(null) }, enabled = !state.busy, label = { Text(names[route!!.ordinal]) },
                        trailingIcon = { Icon(painterResource(CommonR.drawable.ic_close), null, Modifier.size(16.dp)) })
                    if (date != null) FilterChip(true, { vm.date(null) }, enabled = !state.busy, label = { Text(date!!) },
                        trailingIcon = { Icon(painterResource(CommonR.drawable.ic_close), null, Modifier.size(16.dp)) })
                }
            }
        }
        if (state.countsFailed) item { RecoowNotice(stringResource(R.string.history_calendar_retry),
            actions = listOf(RecoowAction(stringResource(CommonR.string.record_retry), onClick = vm::retry))) }
        if (state.entries.isEmpty() && state.loading) item { RecordSkeleton(rows = 3) }
        if (state.entries.isEmpty() && !state.loading && !state.failed) item {
            val relaxed = date != null || route != null || search.isNotBlank()
            RecoowGroup { RecoowEmptyState(title = stringResource(if (relaxed) R.string.history_no_matches else R.string.history_empty),
                icon = CommonR.drawable.ic_clock, actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                onAction = if (relaxed) ({ closeSearch(); vm.route(null); vm.date(null) }) else null) }
        }
        state.entries.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, entries) ->
            item(key = "day:$day") {
                Column {
                    val title = if (day == LocalDate.now()) stringResource(R.string.history_today)
                        else day.format(DateTimeFormatter.ofPattern(when {
                            locale.language == "zh" && day.year == LocalDate.now().year -> "M月d日 E"
                            locale.language == "zh" -> "yyyy年M月d日"
                            day.year == LocalDate.now().year -> "EEE, MMM d"
                            else -> "MMM d, yyyy"
                        }, locale))
                    RecoowSectionHeader(title, trailing = stringResource(R.string.history_count, entries.size))
                    RecoowGroup {
                        entries.forEachIndexed { index, entry ->
                            key(entry.key) { HistoryRow(entry, vm, state, onRecord) }
                            if (index < entries.lastIndex) RecoowDivider(RecoowLayout.coverDividerInset)
                        }
                    }
                }
            }
        }
        if (!state.loading || state.entries.isNotEmpty()) item {
            RecordListFooter(loading = state.loading, failed = state.failed,
                hasMore = state.next != null && !state.loading && !state.failed,
                endLabel = if (state.entries.isNotEmpty() && state.next == null && !state.loading && !state.failed) stringResource(CommonR.string.record_end) else null,
                onRetry = vm::retry, onMore = vm::more)
        }
        state.deleteResult?.let { result -> item { RecoowNotice(stringResource(R.string.history_delete_result, result.deleted.size, result.failed.size)) } }
    }
    if (filters) HistoryFilterSheet(route, date, { filters = false }) { selectedRoute, selectedDate ->
        vm.route(selectedRoute); vm.date(selectedDate); filters = false
    }
    state.plan?.let { plan -> AlertDialog(onDismissRequest = vm::dismiss,
        title = { Text(stringResource(R.string.history_delete_selected, plan.targets.size)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_delete_effects))
            plan.targets.forEach { target -> Text("${names[target.entry.route.ordinal]} · ${target.entry.title}" +
                if (target.entry.route == ToolRoute.FOOD) " · ${stringResource(R.string.history_count, target.entry.count)}" else "") }
        } },
        confirmButton = { TextButton(onClick = vm::delete, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = vm::dismiss, enabled = !state.busy) { Text(stringResource(CommonR.string.record_cancel)) } }) }
    if (state.error) RecordProblem(R.string.history_changed, vm::dismiss)
}
