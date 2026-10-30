package imehc.recoow.core.media

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
fun AudioRecorderInput(onRecorded: (PreparedMedia) -> Unit) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0) }
    var error by remember { mutableStateOf<Int?>(null) }
    val recorder = remember { AudioRecorder(context.applicationContext) { recording = false; error = R.string.recording_interrupted } }
    val latestRecorded by rememberUpdatedState(onRecorded)
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
        onDispose { lifecycle.removeObserver(observer); recorder.cancel() }
    }
    LaunchedEffect(recording) { while (recording) { elapsed = recorder.elapsedSeconds; delay(200) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recording) Text(stringResource(R.string.recording, durationLabel(elapsed)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                if (recording) {
                    try { val content = recorder.finish(); recording = false; latestRecorded(content) }
                    catch (_: Exception) { recording = false; error = R.string.failed }
                } else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(stringResource(if (recording) R.string.stop else R.string.record)) }
            if (recording) TextButton(onClick = { recorder.cancel(); recording = false }) { Text(stringResource(R.string.cancel)) }
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun AudioPlayback(locator: MediaLocator, repository: MediaRepository, durationSeconds: Double?, modifier: Modifier = Modifier) {
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
    Column(modifier) {
        Row {
            TextButton(enabled = content.availability == MediaAvailability.READY && !failed, onClick = {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }) { Text(stringResource(if (playing) R.string.pause else R.string.play)) }
            Text("${durationLabel(position / 1000)} / ${durationLabel(duration / 1000)}", Modifier.padding(12.dp))
        }
        Slider(position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { position = it.toLong(); player.seekTo(position) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = content.availability == MediaAvailability.READY && duration > 0)
        if (failed) Text(stringResource(R.string.audio_failed), color = MaterialTheme.colorScheme.error)
        else if (content.availability != MediaAvailability.READY) Text(stringResource(R.string.pending))
    }
}
