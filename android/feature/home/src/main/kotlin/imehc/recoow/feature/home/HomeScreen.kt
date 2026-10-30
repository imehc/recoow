package imehc.recoow.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.reminders.Reminder
import java.time.ZoneId
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(preferences: AppPreferences? = null, reminders: List<Reminder> = emptyList(), activeTrack: imehc.recoow.core.domain.tracks.Track? = null, onTool: (String) -> Unit = {}) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = System.currentTimeMillis(); delay(30_000) } } }
    val names = LocalResources.current.getStringArray(R.array.home_tool_names)
    RecoowPage(stringResource(R.string.home_title)) {
        ToolRoute.entries.forEachIndexed { index, tool -> if (tool.id !in preferences?.hiddenHomeTools.orEmpty()) { item {
            Card(onClick = { onTool(tool.id) }, modifier = Modifier.fillMaxWidth().testTag("home_${tool.id}")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(names[index], style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(when (tool) {
                        ToolRoute.BILLS -> R.string.home_bills_description; ToolRoute.REMINDERS -> R.string.home_reminders_description
                        ToolRoute.DIARY -> R.string.home_diary_description; ToolRoute.FOOD -> R.string.home_food_description
                        ToolRoute.ITEMS -> R.string.home_items_description; ToolRoute.ANNIVERSARIES -> R.string.home_anniversaries_description
                        ToolRoute.DECISION -> R.string.home_decision_description; ToolRoute.LOCATION -> R.string.home_tracks_description
                    }), style = MaterialTheme.typography.bodyMedium)
                    if (tool == ToolRoute.LOCATION && activeTrack != null) Text(stringResource(if (activeTrack.status == imehc.recoow.core.domain.tracks.TrackStatus.RECORDING) R.string.home_track_recording else R.string.home_track_paused, activeTrack.name))
                    if (tool == ToolRoute.REMINDERS) Text(stringResource(R.string.home_pending, reminders.count { it.canCheckIn(now, ZoneId.systemDefault()) }))
                }
            }
        }
        if (tool == ToolRoute.REMINDERS) {
            reminders.filter { it.canCheckIn(now, ZoneId.systemDefault()) }
                .sortedBy { it.occurrence(java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(), ZoneId.systemDefault()) }.take(5).forEach { r -> item(key = r.id) {
                OutlinedCard(onClick = { onTool("reminders?recordId=${java.net.URLEncoder.encode(r.id, "UTF-8")}") }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { Text("${r.icon} ${r.title}", style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.home_checkin)) }
                }
            } }
        }
        } }
    }
}
