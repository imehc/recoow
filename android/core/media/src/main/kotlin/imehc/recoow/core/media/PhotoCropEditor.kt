package imehc.recoow.core.media

import imehc.recoow.core.designsystem.recordControlDescription
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.core.graphics.withClip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import imehc.recoow.core.domain.media.PreparedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoCropEditor(source: PreparedMedia, processor: ImageProcessor, onCancel: () -> Unit, onConfirm: (PreparedMedia) -> Unit) {
    var zoom by rememberSaveable(source.file.path) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(source.file.path) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(source.file.path) { mutableFloatStateOf(0f) }
    var rotation by rememberSaveable(source.file.path) { mutableFloatStateOf(0f) }
    var bitmap by remember(source.file) { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(source.file) {
        try { bitmap = processor.bitmap(source.file) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
    }
    // Compose may still use the bitmap during a frame; allow GC to release this small bounded preview.
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(R.string.crop)) }, navigationIcon = {
                TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            }, actions = {
                TextButton(enabled = bitmap != null && !busy, onClick = {
                    busy = true; error = false
                    scope.launch {
                        try { onConfirm(processor.crop(source.file, CropTransform(zoom, panX, panY, rotation))) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val moveLeft = stringResource(R.string.crop_move_left)
                val moveRight = stringResource(R.string.crop_move_right)
                val moveUp = stringResource(R.string.crop_move_up)
                val moveDown = stringResource(R.string.crop_move_down)
                Canvas(Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(1f)
                    .recordControlDescription(stringResource(R.string.crop_preview))
                    .semantics {
                        if (!busy) customActions = listOf(
                            CustomAccessibilityAction(moveLeft) { panX = (panX - 0.1f).coerceAtLeast(-2f); true },
                            CustomAccessibilityAction(moveRight) { panX = (panX + 0.1f).coerceAtMost(2f); true },
                            CustomAccessibilityAction(moveUp) { panY = (panY - 0.1f).coerceAtLeast(-2f); true },
                            CustomAccessibilityAction(moveDown) { panY = (panY + 0.1f).coerceAtMost(2f); true },
                        )
                    }.pointerInput(busy) {
                    if (!busy) detectTransformGestures { _, pan, scale, angle ->
                        zoom = (zoom * scale).coerceIn(1f, 4f)
                        panX = (panX + pan.x / size.width).coerceIn(-2f, 2f)
                        panY = (panY + pan.y / size.height).coerceIn(-2f, 2f)
                        rotation = (rotation + angle).coerceIn(-180f, 180f)
                    }
                }) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.withClip(0f, 0f, size.width, size.height) {
                            drawColor(android.graphics.Color.WHITE)
                            bitmap?.let { drawBitmap(it, CropTransform(zoom, panX, panY, rotation).matrix(it.width, it.height, size.width), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
                        }
                    }
                }
                Text(stringResource(R.string.zoom))
                Slider(zoom, { zoom = it }, enabled = !busy, valueRange = 1f..4f, modifier = Modifier.recordControlDescription(stringResource(R.string.zoom), "${"%.1f".format(zoom)}×"))
                Text("${stringResource(R.string.rotation)} ${rotation.toInt()}°")
                Slider(rotation, { rotation = it }, enabled = !busy, valueRange = -180f..180f, modifier = Modifier.recordControlDescription(stringResource(R.string.rotation), "${rotation.toInt()}°"))
                TextButton(enabled = !busy, onClick = { zoom = 1f; panX = 0f; panY = 0f; rotation = 0f }) { Text(stringResource(R.string.reset)) }
                if (busy || bitmap == null && !error) CircularProgressIndicator()
                if (error) Text(stringResource(R.string.failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
