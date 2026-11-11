package imehc.recoow.core.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.media.MediaAttachment
import imehc.recoow.core.domain.media.MediaRepository

/** 共用图片网格：点击预览、长按拖动换位；排序和移除只修改调用方的草稿。 */
@Composable
fun AttachmentPhotoGrid(photos: List<MediaAttachment>, repository: MediaRepository, onPreview: (MediaAttachment) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, onAdd: (() -> Unit)? = null,
    onRemove: ((MediaAttachment) -> Unit)? = null, onMove: ((String, String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(RecoowLayout.rowHorizontal)) {
    var dragged by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    var lastTarget by remember { mutableStateOf<String?>(null) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val latestMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    LaunchedEffect(photos.map { it.id }) {
        bounds.keys.toList().filter { id -> photos.none { it.id == id } }.forEach { bounds.remove(it) }
        if (photos.none { it.id == dragged }) dragged = null
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(contentPadding)) {
        val tileSize = (maxWidth - 16.dp) / 3
        // 在稳定的网格坐标系里检测手势，避免移动格子改变拖动增量。
        val dragModifier = if (enabled && onMove != null) Modifier.pointerInput(enabled) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pointer ->
                    val photo = bounds.entries.firstOrNull { it.value.contains(pointer) }
                    dragged = photo?.key; lastTarget = null; delta = Offset.Zero
                    origin = photo?.value?.center ?: Offset.Zero
                    if (photo != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = { dragged = null; delta = Offset.Zero },
                onDragCancel = { dragged = null; delta = Offset.Zero },
                onDrag = { change, amount ->
                    dragged?.let { id ->
                        change.consume(); delta += amount
                        val center = origin + delta
                        val target = bounds.entries.firstOrNull { it.key != id && it.value.contains(center) }
                        if (target != null && target.key != lastTarget) {
                            lastTarget = target.key; latestMove?.invoke(id, target.key)
                        } else if (target == null && bounds[id]?.contains(center) == true) lastTarget = null
                    }
                },
            )
        } else Modifier
        FlowRow(modifier = dragModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEachIndexed { index, photo -> key(photo.id) {
                Box(Modifier.width(tileSize).zIndex(if (dragged == photo.id) 1f else 0f)
                    .onGloballyPositioned { bounds[photo.id] = it.boundsInParent() }) {
                    AttachmentPhotoTile(photo, repository, { onPreview(photo) }, Modifier.fillMaxWidth()
                        .testTag("attachment_photo_${photo.id}")
                        .graphicsLayer {
                            translationX = 0f; translationY = 0f; scaleX = 1f; scaleY = 1f
                            if (dragged == photo.id) {
                                val shift = origin + delta - (bounds[photo.id]?.center ?: origin)
                                translationX = shift.x; translationY = shift.y; scaleX = 1.03f; scaleY = 1.03f
                            }
                        }.semantics {
                            if (enabled && onMove != null) customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(photo.id, photos[index - 1].id); true })
                                if (index < photos.lastIndex) add(CustomAccessibilityAction(moveDown) { onMove(photo.id, photos[index + 1].id); true })
                            }
                        }, onRemove = onRemove?.let { { it(photo) } }, enabled = enabled)
                }
            } }
            if (onAdd != null) Surface(onClick = onAdd, enabled = enabled, modifier = Modifier.size(tileSize).testTag("diary_add_photo"),
                shape = RoundedCornerShape(RecoowLayout.thumbnailRadius), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(CommonR.drawable.ic_plus), null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.add_photo), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
