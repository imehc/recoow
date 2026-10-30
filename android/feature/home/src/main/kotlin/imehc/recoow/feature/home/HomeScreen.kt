package imehc.recoow.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.tracks.*
import java.time.format.DateTimeFormatter

@Composable
fun HomeRoute(vm: HomeViewModel, preferences: AppPreferences?, activeTrack: Track?, onSettings: () -> Unit,
    onActiveTrack: () -> Unit, onTool: (String) -> Unit) {
    val summary by vm.state.collectAsStateWithLifecycle()
    RecordRefreshEffect { vm.refresh() }
    HomeScreen(preferences, summary, activeTrack, onSettings, vm::retry, onTool, onActiveTrack)
}
@Composable
fun HomeScreen(preferences: AppPreferences? = null, summary: HomeSummary = HomeSummary(), activeTrack: Track? = null,
    onSettings: () -> Unit = {}, onRetry: () -> Unit = {}, onTool: (String) -> Unit = {},
    onActiveTrack: () -> Unit = { onTool(ToolRoute.LOCATION.id) }) {
    val locale = LocalConfiguration.current.locales[0]
    val names = LocalResources.current.getStringArray(R.array.home_tool_names)
    val groups = listOf(R.string.home_daily to listOf(ToolRoute.DIARY, ToolRoute.FOOD, ToolRoute.BILLS, ToolRoute.LOCATION),
        R.string.home_utilities to listOf(ToolRoute.REMINDERS, ToolRoute.ANNIVERSARIES, ToolRoute.DECISION, ToolRoute.ITEMS))
    val hidden = preferences?.hiddenHomeTools.orEmpty()
    RecoowPage(stringResource(R.string.home_title)) {
        item { Text(summary.date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale)), style = MaterialTheme.typography.bodyMedium) }
        if (activeTrack != null) item {
            Card(onClick = onActiveTrack, modifier = Modifier.fillMaxWidth().testTag("home_active_track")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (activeTrack.status == TrackStatus.RECORDING) R.string.home_track_recording else R.string.home_track_paused, activeTrack.name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_track_metrics, activeTrack.duration, activeTrack.pointCount))
                }
            }
        }
        if (summary.failed) item { TextButton(onClick = onRetry) { Text(stringResource(R.string.home_summary_retry)) } }
        if (ToolRoute.entries.all { it.id in hidden }) item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.home_hidden_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_hidden_description))
                Button(onClick = onSettings) { Text(stringResource(R.string.home_settings)) }
            } }
        }
        groups.forEach { (label, tools) ->
            val visible = tools.filter { it.id !in hidden }
            if (visible.isNotEmpty()) item { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) }
            visible.forEach { tool ->
                item(key = tool.id) {
                    Card(onClick = { onTool(tool.id) }, modifier = Modifier.fillMaxWidth().testTag("home_${tool.id}")) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(names[tool.ordinal], style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(when (tool) {
                                ToolRoute.BILLS -> R.string.home_bills_description; ToolRoute.REMINDERS -> R.string.home_reminders_description
                                ToolRoute.DIARY -> R.string.home_diary_description; ToolRoute.FOOD -> R.string.home_food_description
                                ToolRoute.ITEMS -> R.string.home_items_description; ToolRoute.ANNIVERSARIES -> R.string.home_anniversaries_description
                                ToolRoute.DECISION -> R.string.home_decision_description; ToolRoute.LOCATION -> R.string.home_tracks_description
                            }), style = MaterialTheme.typography.bodyMedium)
                            if (tool == ToolRoute.REMINDERS && summary.remindersReady) Text(stringResource(R.string.home_pending, summary.pending.size))
                            if (tool == ToolRoute.ANNIVERSARIES && summary.anniversariesReady && summary.anniversaryDays != null) {
                                Text(if (summary.anniversaryDays == 0L) stringResource(R.string.home_anniversary_today, summary.anniversaryCount)
                                    else stringResource(R.string.home_anniversary_days, summary.anniversaryDays))
                            }
                        }
                    }
                }
                if (tool == ToolRoute.REMINDERS) summary.pending.take(5).forEach { r -> item(key = "reminder:${r.id}") {
                    OutlinedCard(onClick = { onTool("reminders?recordId=${java.net.URLEncoder.encode(r.id, "UTF-8")}") }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) { Text("${r.icon} ${r.title}", style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.home_checkin)) }
                    }
                } }
            }
        }
    }
}
