package imehc.recoow.feature.tracks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.tracks.TrackStatus
import imehc.recoow.core.location.*

@Composable internal fun RecordingPage(vm: TracksViewModel, state: TracksState, recording: RecordingState, back: () -> Unit) {
    val context = LocalContext.current
    val precision by vm.accuracy.collectAsStateWithLifecycle()
    val detail by vm.activeDetail.collectAsStateWithLifecycle()
    var previewRefresh by remember { mutableIntStateOf(0) }
    var previewPermissionAsked by rememberSaveable { mutableStateOf(false) }
    var permissionInFlight by rememberSaveable { mutableStateOf(false) }
    var startAfterPermission by rememberSaveable { mutableStateOf(false) }
    val preview = rememberLocationPreview(enabled = !recording.running, refreshKey = previewRefresh)
    val hasLocationPermission = {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val defaultName = stringResource(R.string.track_default_name, recordDateTime(System.currentTimeMillis()))
    val start = { vm.recorder.requestStart(defaultName, precision) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val shouldStart = startAfterPermission
        startAfterPermission = false
        permissionInFlight = false
        previewRefresh++
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && shouldStart) start()
    }
    val requestLocationPermission: (Boolean) -> Unit = { forRecording ->
        if (!permissionInFlight) {
            previewPermissionAsked = true
            startAfterPermission = forRecording
            permissionInFlight = true
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(Unit) {
        if (!previewPermissionAsked && !recording.running) {
            previewPermissionAsked = true
            if (!hasLocationPermission()) requestLocationPermission(false)
        }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val current = recording.current
    val primaryAction: () -> Unit = {
        if (recording.running) vm.recorder.requestPause()
        else if (hasLocationPermission()) start()
        else requestLocationPermission(true)
    }
    RecordPage(stringResource(R.string.track_title), back, tag = "tool_locationTracker", busy = state.busy || recording.busy, compact = true,
        topActions = { IconButton(onClick = vm::showHistory, enabled = !state.busy) {
            Icon(painterResource(CommonR.drawable.ic_clock), stringResource(R.string.track_history), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, bottomBar = {
            RecoowBottomAction(stringResource(if (recording.running) R.string.track_pause else if (current == null) R.string.track_start else R.string.track_resume),
                primaryAction, enabled = !recording.busy && !state.loading && !state.failed && !permissionInFlight, modifier = Modifier.testTag("track_record_action"),
                secondaryLabel = if (current != null) stringResource(R.string.track_finish) else null,
                onSecondaryClick = vm.recorder::requestFinish, secondaryEnabled = !recording.busy && !permissionInFlight)
        }) {
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading && state.tracks.isEmpty()) item { RecordSkeleton(rows = 2) }
        else if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item(key = "current") {
            Column {
                RecoowSectionHeader(stringResource(R.string.track_current))
                RecoowGroup {
                    Row(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current?.name ?: stringResource(R.string.track_ready), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (current != null) TrackStatusBadge(if (recording.running) TrackStatus.RECORDING else current.status)
                    }
                    RecoowDivider(inset = 0.dp)
                    TrackMetrics(current?.distance ?: 0.0, recording.duration, recording.pointCount, current?.maxSpeed)
                    if (current != null) {
                        RecoowDivider(inset = 0.dp)
                        RecoowRow(title = stringResource(R.string.track_details), showChevron = true, onClick = { vm.show(current.id) })
                    }
                }
            }
        }
        item(key = "settings") {
            RecordFormSection(stringResource(R.string.track_settings)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 10, 5).forEach { value -> FilterChip((current?.accuracy ?: precision) == value, { vm.accuracy(value) },
                        enabled = current == null && !recording.busy, label = { Text(stringResource(R.string.track_accuracy_value, value)) }) }
                }
                Text(stringResource(R.string.track_accuracy_help), Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal).padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (current != null) item(key = "latest") {
            Column {
                RecoowSectionHeader(stringResource(R.string.track_latest_point))
                RecoowGroup {
                    RecoowTextValueRow(stringResource(R.string.track_coordinate_label), recording.latest?.let {
                        stringResource(R.string.track_coordinate, it.coordinate.latitude, it.coordinate.longitude)
                    } ?: stringResource(R.string.track_waiting))
                    recording.latest?.let { point ->
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.track_sample_time), trackDateTime(point.time))
                    }
                }
            }
        }
        if (recording.problem != null) item(key = "problem") {
            RecoowNotice(stringResource(when (recording.problem) {
                RecordingProblem.PERMISSION -> R.string.track_permission; RecordingProblem.PROVIDER -> R.string.track_provider
                RecordingProblem.CHANGED -> R.string.track_dataset_changed; RecordingProblem.INTERRUPTED -> R.string.track_interrupted
                RecordingProblem.SAVE -> R.string.track_save_failed; else -> R.string.track_start_failed
            }), actions = buildList {
                if (recording.problem == RecordingProblem.PERMISSION) add(RecoowAction(stringResource(R.string.track_app_settings)) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                })
                if (recording.problem == RecordingProblem.PROVIDER) add(RecoowAction(stringResource(R.string.track_location_settings)) {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                })
            })
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) item(key = "notification") {
            RecoowGroup { RecoowRow(title = stringResource(R.string.track_enable_notification), showChevron = true,
                onClick = { notification.launch(Manifest.permission.POST_NOTIFICATIONS) }) }
        }
        item(key = "map") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackMapPanel(detail.points, detail.segments,
                    marker = if (recording.running) recording.latest?.coordinate else preview.fix?.coordinate,
                    focus = if (recording.running) recording.latest?.let { MapFocus(listOf(it.coordinate), it.time.toString()) }
                        else preview.anchor?.let { MapFocus(listOf(it.coordinate), "preview:${it.timestamp}") })
                if (!recording.running) when (preview.problem) {
                    LocationPreviewProblem.PERMISSION -> RecoowNotice(stringResource(R.string.track_preview_permission), actions = listOf(
                        RecoowAction(stringResource(R.string.track_preview_enable), enabled = !permissionInFlight) { requestLocationPermission(false) },
                        RecoowAction(stringResource(R.string.track_app_settings)) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        },
                    ))
                    LocationPreviewProblem.PROVIDER -> RecoowNotice(stringResource(R.string.track_preview_provider), actions = listOf(
                        RecoowAction(stringResource(R.string.track_location_settings)) {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                        RecoowAction(stringResource(CommonR.string.record_retry)) { previewRefresh++ },
                    ))
                    null -> Text(stringResource(if (preview.fix == null) R.string.track_preview_waiting else R.string.track_preview_help),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "help") { RecoowNotice(stringResource(R.string.track_background_help)) }
    }
}
