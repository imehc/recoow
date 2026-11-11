package imehc.recoow.core.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay

/** 拖动坐标基于视口，因此移动格子不会改变指针增量。 */
@Stable
class PhotoGridDragState internal constructor(private val grid: LazyGridState) {
    var draggedId by mutableStateOf<String?>(null)
        internal set
    internal var center by mutableStateOf(Offset.Zero)
    internal var pointer by mutableStateOf(Offset.Zero)
    internal var lastTarget: String? = null
    internal fun bounds(id: String): Rect? = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let {
        Rect(it.offset.x.toFloat(), it.offset.y.toFloat(), (it.offset.x + it.size.width).toFloat(), (it.offset.y + it.size.height).toFloat())
    }
    fun translation(id: String): Offset = if (draggedId == id) center - (bounds(id)?.center ?: center) else Offset.Zero
    internal fun target(): String? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        it.key != draggedId && bounds(it.key.toString())?.contains(center) == true
    }?.key?.toString()
    internal fun clear() { draggedId = null; lastTarget = null }
}

@Composable
fun rememberPhotoGridDragState(grid: LazyGridState, onMove: (String, String) -> Unit): PhotoGridDragState {
    val state = remember(grid) { PhotoGridDragState(grid) }
    val move by rememberUpdatedState(onMove)
    LaunchedEffect(state.draggedId) {
        if (state.draggedId != null) while (true) {
            val layout = grid.layoutInfo
            val edge = 64f
            val delta = when {
                state.pointer.y < layout.viewportStartOffset + edge -> -14f
                state.pointer.y > layout.viewportEndOffset - edge -> 14f
                else -> 0f
            }
            if (delta != 0f) {
                grid.scroll { scrollBy(delta) }
                state.target()?.let { target ->
                    if (target != state.lastTarget) { state.lastTarget = target; state.draggedId?.let { move(it, target) } }
                }
            }
            delay(16)
        }
    }
    return state
}

@Composable
fun Modifier.photoGridReorder(state: PhotoGridDragState, ids: List<String>, enabled: Boolean,
    onMove: (String, String) -> Unit, onFinish: (Boolean) -> Unit): Modifier {
    val currentIds by rememberUpdatedState(ids)
    val move by rememberUpdatedState(onMove)
    val finish by rememberUpdatedState(onFinish)
    val haptics = LocalHapticFeedback.current
    return if (!enabled) this else pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { pointer ->
                val id = currentIds.firstOrNull { state.bounds(it)?.contains(pointer) == true }
                state.draggedId = id; state.pointer = pointer
                state.center = id?.let { state.bounds(it)?.center } ?: pointer
                state.lastTarget = null
                if (id != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragCancel = { if (state.draggedId != null) finish(false); state.clear() },
            onDragEnd = { if (state.draggedId != null) finish(true); state.clear() },
            onDrag = { change, amount -> state.draggedId?.let { id ->
                change.consume(); state.pointer = change.position; state.center += amount
                val target = state.target()?.takeIf { it in currentIds }
                if (target != null && target != state.lastTarget) { state.lastTarget = target; move(id, target) }
                else if (target == null && state.bounds(id)?.contains(state.center) == true) state.lastTarget = null
            } },
        )
    }
}

/** 只消费双指变化；单指滚动和预览点击仍交给系统处理。 */
@Composable
fun Modifier.photoGridDensity(columns: Int, enabled: Boolean, onColumns: (Int) -> Unit): Modifier {
    val current by rememberUpdatedState(columns)
    val change by rememberUpdatedState(onColumns)
    return if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    if (event.changes.any { it.position != it.previousPosition }) event.changes.forEach { it.consume() }
                    if (zoom > 1.2f) { change((current - 1).coerceIn(3, 6)); zoom = 1f }
                    else if (zoom < 0.8f) { change((current + 1).coerceIn(3, 6)); zoom = 1f }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
