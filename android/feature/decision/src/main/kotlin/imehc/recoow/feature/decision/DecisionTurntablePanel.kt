package imehc.recoow.feature.decision

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.decision.Turntable
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.scene.DecisionScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.PI
import kotlin.math.roundToInt

/** 拖动直接更新光标；浏览与抽选动画可取消，不参与随机结果计算。 */
@Stable internal class DecisionTurntableMotion(private val scope: CoroutineScope) {
    var cursor by mutableFloatStateOf(0f)
        private set
    var isAnimating by mutableStateOf(false)
        private set
    var isDragging by mutableStateOf(false)
        private set
    private var activeMotion: Job? = null
    private var generation = 0
    fun stop() { generation++; activeMotion?.cancel(); activeMotion = null; isAnimating = false; isDragging = false }
    fun beginDrag() { stop(); isDragging = true }
    fun endDrag() { isDragging = false }
    fun snap(index: Int, count: Int) { stop(); cursor = Turntable.wrap(index, count).toFloat() }
    fun drag(amount: Float, step: Float) { cursor -= amount / step }
    fun browse(index: Int, count: Int, animated: Boolean = true) {
        stop()
        val target = Turntable.browse(index, count, cursor.toDouble()).toFloat()
        activeMotion = scope.launch(start = CoroutineStart.UNDISPATCHED) { move(target, 200, animated, false) }
    }
    fun settle(count: Int, velocity: Float = 0f) {
        // 轻微延续甩动方向，最多多走一项，避免浏览变成抽选。
        val projected = cursor + (velocity * .08f).coerceIn(-1f, 1f)
        if (count > 0) browse(projected.roundToInt(), count)
    }
    suspend fun spin(target: Float, animated: Boolean) { stop(); move(target, 2200, animated, true) }
    private suspend fun move(target: Float, duration: Int, animated: Boolean, spin: Boolean) {
        val current = generation
        activeMotion = currentCoroutineContext()[Job]
        isAnimating = true
        try {
            if (animated && ValueAnimator.areAnimatorsEnabled()) animate(cursor, target,
                animationSpec = tween(duration, easing = if (spin) Easing { t -> 1f - (1f - t).pow(5) * (1f + 5f * t) } else LinearOutSlowInEasing)) { value, _ -> cursor = value }
            cursor = target
        } finally { if (current == generation) { isAnimating = false; activeMotion = null } }
    }
}

@Composable internal fun rememberDecisionTurntableMotion(): DecisionTurntableMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { DecisionTurntableMotion(scope) }
}

/** 真实抽选和模型预览共用场景、加载 / 重试和直接拖动；主操作放在场景外。 */
@Composable internal fun DecisionTurntablePanel(titles: List<String>, motion: DecisionTurntableMotion, dark: Boolean,
    height: Dp, interactive: Boolean, modelKeys: List<String>? = null) {
    var loaded by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var unavailable by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val canInteract by rememberUpdatedState(interactive)
    val sceneShape = RoundedCornerShape(RecoowLayout.cardRadius)
    val description = stringResource(R.string.decision_scene_description)
    val scheme = MaterialTheme.colorScheme
    val background = scheme.background
    val accent = RecoowToolIcons.tint(ToolRoute.DECISION)
    val surfaceColors = remember(scheme, accent) { mapOf(
        "RD_Base" to scheme.surfaceContainerHighest,
        "RD_Deck" to scheme.surface,
        "RD_Line" to scheme.outline,
        "RD_Accent" to accent,
        "RD_Card" to scheme.surface,
        "RD_Surface" to scheme.surfaceContainerLow,
    ) }
    Box(Modifier.fillMaxWidth().height(height).clip(sceneShape).background(background)
        .testTag(if (loaded) "decision_scene_ready" else "decision_scene_loading")) {
        if (!unavailable) key(generation) {
            DecisionScene(titles, 0.0, dark, Modifier.fillMaxSize(), onLoaded = { loaded = true },
                onFailure = { unavailable = true; loaded = false }, modelKeys = modelKeys,
                cursorProvider = { motion.cursor.toDouble() }, backgroundColor = background, surfaceColors = surfaceColors,
                onLoading = { loaded = false }, onProgress = { progress = it },
                deferModelLoading = { motion.isDragging || motion.isAnimating })
        }
        if (!unavailable && loaded) Box(Modifier.matchParentSize().semantics { contentDescription = description }
            .pointerInput(interactive, titles.size) {
                // 与正交相机的宽高适配一致，前侧模型沿切线跟随手指。
                val visibleWidth = maxOf(4.96f, 3.6f * size.width / size.height.coerceAtLeast(1))
                val step = (size.width * (2 * PI * 1.5).toFloat() / visibleWidth / titles.size.coerceIn(2, Turntable.LIMIT)).coerceAtLeast(32f)
                val velocity = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { velocity.resetTracking(); if (canInteract) motion.beginDrag() },
                    onDragEnd = { motion.endDrag(); if (canInteract) motion.settle(titles.size, -velocity.calculateVelocity().x / step) },
                    onDragCancel = { motion.endDrag(); if (canInteract) motion.settle(titles.size) },
                ) { change, amount -> if (canInteract && titles.size > 1) {
                    change.consume(); velocity.addPosition(change.uptimeMillis, change.position); motion.drag(amount, step)
                } }
            })
        if (!loaded && !unavailable) Box(Modifier.matchParentSize().background(background)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        if (loaded && !unavailable && progress < 1f) LinearProgressIndicator(progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
            color = scheme.outline, trackColor = background)
        if (unavailable) Column(Modifier.align(Alignment.Center).padding(RecoowLayout.pagePadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.decision_scene_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = { unavailable = false; loaded = false; generation++ }) { Text(stringResource(CommonR.string.record_retry)) }
        }
    }
}
