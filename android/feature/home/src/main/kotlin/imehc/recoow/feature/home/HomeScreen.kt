package imehc.recoow.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.tracks.Track
import imehc.recoow.core.domain.tracks.TrackStatus
import java.net.URLEncoder
import java.time.format.DateTimeFormatter

@Composable
fun HomeRoute(vm: HomeViewModel, preferences: AppPreferences?, activeTrack: Track?, onSettings: () -> Unit,
    onActiveTrack: () -> Unit, onTool: (String) -> Unit) {
    val summary by vm.state.collectAsStateWithLifecycle()
    RecordRefreshEffect { vm.refresh() }
    HomeScreen(preferences, summary, activeTrack, onSettings, vm::retry, onTool, onActiveTrack)
}

/**
 * 首页 = 两段式入口：日常记录 / 生活工具。
 * 每行一个工具，左侧语义色图标、右侧真实状态；状态位不再是描述性占位文案。
 */
@Composable
fun HomeScreen(preferences: AppPreferences? = null, summary: HomeSummary = HomeSummary(), activeTrack: Track? = null,
    onSettings: () -> Unit = {}, onRetry: () -> Unit = {}, onTool: (String) -> Unit = {},
    onActiveTrack: () -> Unit = { onTool(ToolRoute.LOCATION.id) }) {
    val locale = LocalConfiguration.current.locales[0]
    val names = LocalResources.current.getStringArray(R.array.home_tool_names)
    val groups = listOf(
        R.string.home_daily to listOf(ToolRoute.DIARY, ToolRoute.FOOD, ToolRoute.BILLS, ToolRoute.LOCATION),
        R.string.home_utilities to listOf(ToolRoute.REMINDERS, ToolRoute.ANNIVERSARIES, ToolRoute.DECISION, ToolRoute.ITEMS),
    )
    val hidden = preferences?.hiddenHomeTools.orEmpty()
    val firstVisibleGroup = groups.indexOfFirst { (_, routes) -> routes.any { it.id !in hidden } }
    val date = summary.date.format(DateTimeFormatter.ofPattern(if (locale.language == "zh") "M月d日 E" else "EEE, MMM d", locale))
    val pending = if (ToolRoute.REMINDERS.id !in hidden && summary.remindersReady) summary.pending else emptyList()

    RecoowPage(stringResource(R.string.home_title), contentSpacing = RecoowLayout.sectionGap) {
        if (pending.isNotEmpty()) item(key = "pending") {
            Column {
                RecoowSectionHeader(
                    stringResource(R.string.home_pending, pending.size),
                    trailing = if (firstVisibleGroup < 0) date else null,
                )
                RecoowGroup {
                    val shown = pending.take(3)
                    shown.forEachIndexed { index, reminder ->
                        RecoowRow(
                            title = reminder.title,
                            showChevron = true,
                            icon = { Text(reminder.icon, style = MaterialTheme.typography.titleMedium) },
                            onClick = { onTool("reminders?recordId=${URLEncoder.encode(reminder.id, "UTF-8")}") },
                        )
                        if (index < shown.lastIndex || pending.size > 3) RecoowDivider(RecoowLayout.dividerInset)
                    }
                    if (pending.size > 3) {
                        RecoowRow(
                            title = stringResource(R.string.home_more_pending, pending.size - 3),
                            showChevron = true,
                            onClick = { onTool(ToolRoute.REMINDERS.id) },
                        )
                    }
                }
            }
        }
        if (activeTrack != null) item(key = "active_track") {
            RecoowGroup {
                RecoowToolRow(
                    route = ToolRoute.LOCATION,
                    title = stringResource(
                        if (activeTrack.status == TrackStatus.RECORDING) R.string.home_track_recording else R.string.home_track_paused,
                        activeTrack.name,
                    ),
                    status = stringResource(R.string.home_track_metrics, activeTrack.duration, activeTrack.pointCount),
                    onClick = onActiveTrack,
                    modifier = Modifier.testTag("home_active_track"),
                )
            }
        }
        if (summary.failed) item(key = "failed") {
            RecoowGroup {
                RecoowRow(title = stringResource(R.string.home_summary_retry), showChevron = true, onClick = onRetry)
            }
        }
        if (ToolRoute.entries.all { it.id in hidden }) item(key = "hidden") {
            RecoowGroup {
                RecoowEmptyState(
                    title = stringResource(R.string.home_hidden_title),
                    description = stringResource(R.string.home_hidden_description),
                    actionLabel = stringResource(R.string.home_settings),
                    onAction = onSettings,
                )
            }
        }
        groups.forEachIndexed { groupIndex, (label, tools) ->
            val visible = tools.filter { it.id !in hidden }
            if (visible.isNotEmpty()) item(key = "group:$groupIndex") {
                Column {
                    RecoowSectionHeader(
                        stringResource(label),
                        trailing = if (groupIndex == firstVisibleGroup) date else null,
                    )
                    RecoowGroup {
                        visible.forEachIndexed { index, tool ->
                            RecoowToolRow(
                                route = tool,
                                title = names[tool.ordinal],
                                status = homeStatus(tool, summary, activeTrack),
                                statusColor = if (tool == ToolRoute.REMINDERS && summary.remindersReady && summary.pending.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { onTool(tool.id) },
                                modifier = Modifier.testTag("home_${tool.id}"),
                            )
                            if (index < visible.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                        }
                    }
                }
            }
        }
    }
}

/** 入口状态位只放真实数据；没有可说的就不写，不用描述性占位文案。 */
@Composable
private fun homeStatus(tool: ToolRoute, summary: HomeSummary, activeTrack: Track?): String? = when (tool) {
    ToolRoute.REMINDERS -> if (summary.remindersReady && summary.pending.isNotEmpty()) {
        stringResource(R.string.home_pending, summary.pending.size)
    } else null
    ToolRoute.ANNIVERSARIES -> if (summary.anniversariesReady && summary.anniversaryDays != null) {
        if (summary.anniversaryDays == 0L) stringResource(R.string.home_anniversary_today, summary.anniversaryCount)
        else stringResource(R.string.home_anniversary_days, summary.anniversaryDays)
    } else null
    ToolRoute.LOCATION -> activeTrack?.let {
        stringResource(if (it.status == TrackStatus.RECORDING) R.string.home_track_recording else R.string.home_track_paused, it.name)
    }
    else -> null
}
