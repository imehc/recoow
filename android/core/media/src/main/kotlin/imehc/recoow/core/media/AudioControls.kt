package imehc.recoow.core.media

import imehc.recoow.core.designsystem.recordControlDescription
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.R as CommonR
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import imehc.recoow.core.domain.media.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch

internal fun durationLabel(seconds: Long) = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun AudioRecorderInput(modifier: Modifier = Modifier, inline: Boolean = false, enabled: Boolean = true,
    onRecordingChange: (Boolean) -> Unit = {}, onRecorded: (PreparedMedia) -> Unit) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0) }
    var error by remember { mutableStateOf<Int?>(null) }
    val recorder = remember { AudioRecorder(context.applicationContext) { recording = false; error = R.string.recording_interrupted } }
    val latestRecorded by rememberUpdatedState(onRecorded)
    val latestRecordingChange by rememberUpdatedState(onRecordingChange)
    LaunchedEffect(recording) { latestRecordingChange(recording) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) error = R.string.microphone_denied
        else try { recorder.start(); recording = true; error = null }
        catch (_: Exception) { error = R.string.failed }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, recorder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recorder.recording) {
                recorder.cancel(); recording = false; error = R.string.recording_interrupted
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recorder.cancel(); latestRecordingChange(false) }
    }
    LaunchedEffect(recording) { while (recording) { elapsed = recorder.elapsedSeconds; delay(200) } }
    val toggle: () -> Unit = {
        if (recording) {
            try { val content = recorder.finish(); recording = false; latestRecorded(content) }
            catch (_: Exception) { recording = false; error = R.string.failed }
        } else { elapsed = 0; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    val cancel: () -> Unit = { recorder.cancel(); recording = false; elapsed = 0 }
    if (inline) Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = RecoowLayout.rowMinHeight)
            .then(if (!recording && enabled) Modifier.clickable(onClick = toggle) else Modifier)
            .padding(start = RecoowLayout.rowHorizontal, end = RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) {
                Icon(painterResource(CommonR.drawable.ic_microphone), null,
                    tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(RecoowLayout.iconSize))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (recording) R.string.recording else R.string.record, durationLabel(elapsed)), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (recording) IconButton(onClick = cancel, modifier = Modifier.size(RecoowLayout.controlSize)) {
                Icon(painterResource(CommonR.drawable.ic_close), stringResource(R.string.cancel), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = toggle, enabled = enabled || recording, modifier = Modifier.size(RecoowLayout.controlSize)) {
                Icon(painterResource(if (recording) CommonR.drawable.ic_check else CommonR.drawable.ic_plus),
                    stringResource(if (recording) R.string.stop else R.string.record), tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(RecoowLayout.iconSize))
            }
        }
        error?.let { Text(stringResource(it), Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recording) Text(stringResource(R.string.recording, durationLabel(elapsed)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = toggle, enabled = enabled || recording) { Text(stringResource(if (recording) R.string.stop else R.string.record)) }
            if (recording) TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) }
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun AudioPlayback(locator: MediaLocator, repository: MediaRepository, durationSeconds: Double?, modifier: Modifier = Modifier,
    compact: Boolean = false, trailing: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val flow = remember(locator, repository) { repository.observeContent(locator).catch { emit(ResolvedMedia(MediaAvailability.DAMAGED)) } }
    val content by flow.collectAsStateWithLifecycle(ResolvedMedia(MediaAvailability.PENDING))
    val player = remember { ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
    } }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember(durationSeconds) { mutableLongStateOf(((durationSeconds ?: 0.0) * 1000).toLong()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) duration = player.duration.coerceAtLeast(0)
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer); player.addListener(listener)
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(content.file) {
        player.stop(); player.clearMediaItems(); failed = false
        content.file?.let { player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(it))); player.prepare() }
    }
    LaunchedEffect(playing) { while (playing) { position = player.currentPosition; delay(250) }; position = player.currentPosition }
    val ready = content.availability == MediaAvailability.READY && !failed
    val toggle: () -> Unit = {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }
    val durationText = "${durationLabel(position / 1000)} / ${durationLabel(duration / 1000)}"
    Column(modifier) {
        if (compact) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = toggle, enabled = ready, modifier = Modifier.size(RecoowLayout.controlSize)) {
                Icon(painterResource(if (playing) CommonR.drawable.ic_pause else CommonR.drawable.ic_play),
                    stringResource(if (playing) R.string.pause else R.string.play), modifier = Modifier.size(20.dp))
            }
            val trackColor = MaterialTheme.colorScheme.outlineVariant
            val progressColor = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            val range = 0f..duration.coerceAtLeast(1).toFloat()
            val sliderState = remember(range) { SliderState(trackRange = range) }
            sliderState.value = position.toFloat().coerceIn(range)
            Slider(state = sliderState,
                onValueChange = { position = it.toLong(); player.seekTo(position) },
                enabled = ready && duration > 0,
                modifier = Modifier.weight(1f).height(48.dp).recordControlDescription(stringResource(R.string.audio_playback_position), durationText),
                thumb = { Box(Modifier.size(12.dp).background(progressColor, CircleShape)) },
                track = { slider -> Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val fraction = (slider.value / duration.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    drawRoundRect(trackColor, cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(progressColor, size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(size.height / 2))
                } })
            Text(durationText, Modifier.padding(start = 8.dp, end = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            trailing()
        } else {
            Row {
                TextButton(enabled = ready, onClick = toggle) { Text(stringResource(if (playing) R.string.pause else R.string.play)) }
                Text(durationText, Modifier.padding(12.dp))
            }
            Slider(position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { position = it.toLong(); player.seekTo(position) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = ready && duration > 0,
                modifier = Modifier.recordControlDescription(stringResource(R.string.audio_playback_position), durationText))
        }
        if (failed) Text(stringResource(R.string.audio_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else if (content.availability != MediaAvailability.READY) Text(stringResource(R.string.pending), style = MaterialTheme.typography.bodySmall)
    }
}
