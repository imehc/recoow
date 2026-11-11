package imehc.recoow.core.scene

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Renderer
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只负责渲染打包的 GLB；选择逻辑与无障碍替代方案由调用方提供。 */
@Composable
fun GlbScene(
    assetPath: String,
    modifier: Modifier = Modifier,
    onLoaded: () -> Unit = {},
    onFrame: (Long) -> Unit = {},
    onFailure: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val creation = remember(context) { runCatching { SceneSurface(context) } }
    val loaded = rememberUpdatedState(onLoaded)
    val failed = rememberUpdatedState(onFailure)
    val frame = rememberUpdatedState(onFrame)
    val surface = creation.getOrNull()
    if (surface == null) {
        LaunchedEffect(creation) { failed.value(requireNotNull(creation.exceptionOrNull())) }
        return
    }
    surface.onFrame = { frame.value(it) }
    DisposableEffect(surface, owner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = surface.start()
            override fun onPause(owner: LifecycleOwner) = surface.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); surface.stop() }
    }
    LaunchedEffect(surface, assetPath) {
        try {
            val buffer = withContext(Dispatchers.IO) {
                context.assets.open(assetPath).use { ByteBuffer.wrap(it.readBytes()) }
            }
            surface.viewer.loadModelGlb(buffer)
            check(surface.viewer.asset != null) { "Unable to load GLB" }
            surface.viewer.transformToUnitCube()
            loaded.value()
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { failed.value(error) }
    }
    // ModelViewer 在分离时自行销毁原生引擎，不要在 onRelease 里重复销毁。
    AndroidView(factory = { surface }, modifier = modifier, onReset = null, onRelease = { it.release() })
}

private class SceneSurface(context: Context) : SurfaceView(context), Choreographer.FrameCallback {
    val viewer: ModelViewer
    private var active = false
    private var wasAttached = false
    var onFrame: (Long) -> Unit = {}
    init {
        Utils.init()
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { wasAttached = true }
            override fun onViewDetachedFromWindow(v: View) { stop() }
        })
        viewer = ModelViewer(this)
        viewer.autoPlayAnimations = false
        viewer.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true }
        setOnTouchListener(viewer)
    }
    fun start() {
        if (!active) { active = true; Choreographer.getInstance().postFrameCallback(this) }
    }
    fun stop() { active = false; Choreographer.getInstance().removeFrameCallback(this) }
    fun release() {
        stop()
        if (!wasAttached) viewer.destroy()
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!active) return
        if (isAttachedToWindow && windowVisibility == VISIBLE && viewer.render(frameTimeNanos)) onFrame(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }
}
