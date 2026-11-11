package imehc.recoow.feature.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.tracks.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun TrackMetrics(distance: Double, duration: Long, points: Int, maxSpeed: Double?) {
    val seconds = duration.coerceAtLeast(0)
    RecoowMetricStrip(listOf(
        RecoowMetric(stringResource(R.string.track_distance_unit), "%.2f".format(distance / 1000), icon = CommonR.drawable.ic_tool_tracks),
        RecoowMetric(stringResource(if (seconds >= 3600) R.string.track_duration_hm_label else R.string.track_duration_ms_label),
            if (seconds >= 3600) "%d:%02d".format(seconds / 3600, seconds / 60 % 60) else "%d:%02d".format(seconds / 60, seconds % 60), icon = CommonR.drawable.ic_clock),
        RecoowMetric(stringResource(R.string.track_points), points.toString(), icon = CommonR.drawable.ic_location),
        RecoowMetric(stringResource(R.string.track_speed_unit), maxSpeed?.let { "%.1f".format(it * 3.6) } ?: "—", icon = CommonR.drawable.ic_bill_transport),
    ))
}

@Composable internal fun TrackStatusBadge(status: TrackStatus) {
    val tint = when (status) {
        TrackStatus.RECORDING -> MaterialTheme.colorScheme.tertiary
        TrackStatus.PAUSED -> RecoowPalette.warningColor(MaterialTheme.colorScheme.background.luminance() < .5f)
        TrackStatus.FINISHED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    RecordBadge(statusName(status), containerColor = tint.copy(alpha = .12f), contentColor = tint)
}

@Composable internal fun TrackHistoryRow(track: Track, distance: Double = track.distance, duration: Long = track.duration,
    pointCount: Int = track.pointCount, onClick: () -> Unit) {
    val tint = RecoowToolIcons.tint(ToolRoute.LOCATION)
    RecordThumbnailRow(onClick, thumbnail = { TrackThumbnail(CommonR.drawable.ic_tool_tracks, tint) }, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(trackDistance(distance), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) { RecoowChevron() }
        }
    }) {
        Text(track.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(recordDateTime(track.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TrackMetadata(CommonR.drawable.ic_clock, trackDuration(duration))
            TrackMetadata(CommonR.drawable.ic_location, stringResource(R.string.track_point_count, pointCount))
            if (track.status != TrackStatus.FINISHED) TrackStatusBadge(track.status)
        }
    }
}

@Composable internal fun TrackThumbnail(icon: Int, tint: Color) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(RecoowLayout.thumbnailRadius)).background(tint.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize))
    }
}

@Composable internal fun TrackMetadata(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun TrackTextSection(title: String, text: String) {
    Column {
        RecoowSectionHeader(title)
        RecoowGroup { SelectionContainer { Text(text, Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) } }
    }
}

@Composable internal fun trackDistance(meters: Double): String = if (meters < 1000) stringResource(R.string.track_meters_value, meters)
    else stringResource(R.string.track_km_value, meters / 1000)

@Composable internal fun trackSpeed(metersPerSecond: Double?): String = metersPerSecond?.let {
    stringResource(R.string.track_speed_value, "%.1f".format(it * 3.6))
} ?: "—"

@Composable internal fun trackDuration(seconds: Long): String {
    val value = seconds.coerceAtLeast(0)
    return when {
        value >= 3600 -> stringResource(R.string.track_duration_hms, value / 3600, value / 60 % 60, value % 60)
        value >= 60 -> stringResource(R.string.track_duration_ms, value / 60, value % 60)
        else -> stringResource(R.string.track_seconds_value, value)
    }
}

@Composable internal fun statusName(status: TrackStatus) = stringResource(when (status) {
    TrackStatus.RECORDING -> R.string.track_recording; TrackStatus.PAUSED -> R.string.track_paused; TrackStatus.FINISHED -> R.string.track_finished
})
@Composable internal fun motionName(type: MotionType) = stringResource(when (type) {
    MotionType.UNKNOWN -> R.string.track_unknown; MotionType.STATIONARY -> R.string.track_stationary; MotionType.WALKING -> R.string.track_walking
    MotionType.RUNNING -> R.string.track_running; MotionType.CYCLING -> R.string.track_cycling; MotionType.TRANSIT -> R.string.track_transit; MotionType.DRIVING -> R.string.track_driving
})
@Composable internal fun qualityName(kind: TrackQuality) = stringResource(when (kind) {
    TrackQuality.WEAK_ACCURACY -> R.string.track_weak_accuracy; TrackQuality.TIME_GAP -> R.string.track_time_gap; TrackQuality.SPEED_SPIKE -> R.string.track_speed_spike
})
@Composable internal fun trackDateTime(timestamp: Long) = recordDateTime(timestamp)

@Composable internal fun trackTimeRange(start: Long, end: Long): String {
    val zone = ZoneId.systemDefault()
    val startDate = Instant.ofEpochMilli(start).atZone(zone)
    val endDate = Instant.ofEpochMilli(end).atZone(zone)
    val endText = if (startDate.toLocalDate() == endDate.toLocalDate()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0]).format(endDate)
    } else trackDateTime(end)
    return "${trackDateTime(start)} — $endText"
}

@Composable internal fun trackMotionTint(type: MotionType): Color = RecoowToolIcons.tint(when (type) {
    MotionType.WALKING -> ToolRoute.BILLS
    MotionType.RUNNING -> ToolRoute.ANNIVERSARIES
    MotionType.CYCLING -> ToolRoute.ITEMS
    MotionType.TRANSIT -> ToolRoute.DIARY
    MotionType.DRIVING -> ToolRoute.REMINDERS
    else -> ToolRoute.LOCATION
})

internal fun trackMotionIcon(type: MotionType): Int = when (type) {
    MotionType.STATIONARY -> CommonR.drawable.ic_pause
    MotionType.TRANSIT, MotionType.DRIVING -> CommonR.drawable.ic_bill_transport
    MotionType.UNKNOWN -> CommonR.drawable.ic_location
    else -> CommonR.drawable.ic_tool_tracks
}
