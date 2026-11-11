package imehc.recoow.core.scene

import android.content.Context
import android.opengl.Matrix
import android.os.Build
import android.os.Trace
import android.view.Choreographer
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import imehc.recoow.core.domain.decision.Turntable
import java.nio.ByteBuffer
import kotlin.math.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 模型准备跟随原生显示刷新，不让后台等待持续请求 Compose 重组。 */
private suspend fun awaitDecisionFrame(): Unit = suspendCancellableCoroutine { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}

@Composable
fun DecisionScene(titles: List<String>, cursor: Double, dark: Boolean, modifier: Modifier = Modifier,
    onLoaded: () -> Unit = {}, onFailure: (Throwable) -> Unit, onFrame: (Long) -> Unit = {}, modelKeys: List<String>? = null,
    cursorProvider: (() -> Double)? = null, backgroundColor: Color = if (dark) Color(0xFF111214) else Color(0xFFF4F5F7),
    surfaceColors: Map<String, Color> = emptyMap(), onLoading: () -> Unit = {},
    onProgress: (Float) -> Unit = {}, deferModelLoading: () -> Boolean = { false }) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    // 先提交页面和加载状态，再进行必须在主线程执行的原生初始化。
    var creation by remember(context) { mutableStateOf<Result<DecisionSurface>?>(null) }
    LaunchedEffect(context) { awaitDecisionFrame(); creation = runCatching { DecisionSurface(context) } }
    val created = creation ?: return
    val surface = created.getOrNull(); val failed by rememberUpdatedState(onFailure); val loaded by rememberUpdatedState(onLoaded)
    val loading by rememberUpdatedState(onLoading)
    val progress by rememberUpdatedState(onProgress)
    val deferLoading by rememberUpdatedState(deferModelLoading)
    val frame by rememberUpdatedState(onFrame)
    if (surface == null) { LaunchedEffect(created) { failed(created.exceptionOrNull()!!) }; return }
    SideEffect {
        surface.readCursor = cursorProvider ?: { cursor }; surface.onFrame = { frame(it) }
        surface.onLoaded = { loaded() }; surface.onLoading = { loading() }; surface.onFailure = { failed(it) }
    }
    DisposableEffect(surface, owner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = surface.start()
            override fun onPause(owner: LifecycleOwner) = surface.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); surface.stop() }
    }
    LaunchedEffect(surface, titles, dark, modelKeys, backgroundColor, surfaceColors) {
        try {
            surface.beginLoading()
            progress(0f)
            val catalog = DecisionAssets.catalog(context)
            val paths = titles.mapIndexed { index, title -> DecisionAssets.path(catalog, title, modelKeys?.getOrNull(index)) }
            val counts = paths.groupingBy { it }.eachCount().mapValues { min(it.value, Turntable.LIMIT) }
            surface.theme(catalog, dark, backgroundColor, surfaceColors)
            surface.load("decision-stage.glb", DecisionAssets.bytes(context, "decision-stage.glb"), 1)
            awaitDecisionFrame()
            if (paths.isNotEmpty()) surface.load("option-pad.glb", DecisionAssets.bytes(context, "option-pad.glb"), min(paths.size, Turntable.LIMIT))
            surface.paths = paths
            val pending = counts.keys.toMutableSet()
            suspend fun prepareModel(path: String, background: Boolean) {
                try {
                    val buffer = DecisionAssets.bytes(context, path)
                    // IO 返回时手势可能已经开始，上传前再次检查，不抢占拖动帧。
                    if (background) while (deferLoading()) awaitDecisionFrame()
                    surface.load(path, buffer, counts.getValue(path))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    surface.load("option-default.glb", DecisionAssets.bytes(context, "option-default.glb"), min(paths.size, Turntable.LIMIT))
                    surface.useFallback(path)
                }
                pending.remove(path)
                progress(if (counts.isEmpty()) 1f else (counts.size - pending.size).toFloat() / counts.size)
            }
            // 首次只准备当前项；未加载的位置先显示中性的托盘。
            surface.prioritizedModel(pending)?.let { prepareModel(it, background = false) }
            if (counts.isEmpty()) progress(1f)
            surface.ready()
            // 先把有效首帧交给用户，再继续准备模型，避免首帧被后续上传排在后面。
            while (!surface.isRevealed) awaitDecisionFrame()
            while (pending.isNotEmpty()) {
                awaitDecisionFrame()
                if (deferLoading()) continue
                val path = surface.prioritizedModel(pending) ?: break
                prepareModel(path, background = true)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) }
    }
    AndroidView(factory = { surface }, modifier = modifier, onReset = null, onRelease = { it.release() })
}

private class DecisionSurface(context: Context) : SurfaceView(context), Choreographer.FrameCallback {
    private val viewer: ModelViewer
    private val materials: UbershaderProvider
    private val loader: AssetLoader
    private val resources: ResourceLoader
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private data class Model(val asset: FilamentAsset, val instances: List<FilamentInstance>, val isSurface: Boolean)
    private val models = mutableMapOf<String, Model>()
    private val fallbackPaths = mutableSetOf<String>()
    private var visible = emptySet<FilamentInstance>()
    var paths: List<String> = emptyList()
        set(value) { field = value; lastCursor = Double.NaN; renderFrames = 8 }
    var readCursor: () -> Double = { 0.0 }
    var onFrame: (Long) -> Unit = {}
    var onLoaded: () -> Unit = {}
    var onLoading: () -> Unit = {}
    var onFailure: (Throwable) -> Unit = {}
    private var active = false; private var attached = false; private var released = false
    private var sceneReady = false
    private var readyNotificationPending = false
    private var firstFrameFence: Fence? = null
    private var revealOnNextFrame = false
    private var lastCursor = Double.NaN
    private var renderFrames = 8
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var colors: org.json.JSONObject? = null
    private var darkTheme = false
    private var surfaceColors: Map<String, Color> = emptyMap()
    private val transform = FloatArray(16)
    private val deckRotation = FloatArray(16)
    private var firstFramePending = true
    private var deckEntity: Int? = null
    private var deckTransform: FloatArray? = null
    private val traceCookie = System.identityHashCode(this)
    init {
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection("recoow.decision.firstFrame", traceCookie)
        Utils.init()
        // SurfaceView 保持可见以便后台提交首帧，仅隐藏其原生表面，避免创建时露出黑底。
        alpha = 0f
        // 注册在 ModelViewer 的分离监听之前，使我们的资源先于其引擎释放。
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attached = true }
            override fun onViewDetachedFromWindow(v: View) { releaseAssets() }
        })
        viewer = ModelViewer(this, uiHelper = uiHelper, manipulator = null)
        viewer.camera.lookAt(0.0, 6.0, 8.0, 0.0, .3, 0.0, 0.0, 1.0, 0.0)
        viewer.autoPlayAnimations = false
        viewer.cameraFocalLength = 35f
        viewer.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true }
        materials = UbershaderProvider(viewer.engine)
        loader = AssetLoader(viewer.engine, materials, EntityManager.get())
        resources = ResourceLoader(viewer.engine)
        viewer.scene.indirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(.7f, .7f, .7f)).intensity(20_000f).build(viewer.engine)
        setOnTouchListener { _, _ -> false }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { if (!released) hideSurface() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!released) { cameraWidth = 0; renderFrames = 8 }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { if (!released) hideSurface() }
        })
    }
    val isRevealed: Boolean get() = sceneReady && !readyNotificationPending && alpha == 1f
    fun prioritizedModel(pending: Set<String>): String? {
        if (pending.isEmpty()) return null
        val cursor = readCursor()
        Turntable.focused(cursor, paths.size)?.let { index -> paths[index].takeIf { it in pending }?.let { return it } }
        for (placement in Turntable.placements(paths.size, cursor).sortedBy { abs(atan2(sin(it.angle), cos(it.angle))) }) {
            paths[placement.index].takeIf { it in pending }?.let { return it }
        }
        return pending.first()
    }
    fun useFallback(path: String) { fallbackPaths += path; invalidateModels() }
    private fun invalidateModels() { lastCursor = Double.NaN; renderFrames = 8 }
    fun load(path: String, buffer: ByteBuffer, count: Int) {
        if (released || (models[path]?.instances?.size ?: 0) >= count) return
        models.remove(path)?.let { old ->
            for (instance in old.instances) viewer.scene.removeEntities(instance.entities)
            visible = visible - old.instances.toSet()
            loader.destroyAsset(old.asset)
        }
        val instances = arrayOfNulls<FilamentInstance>(count)
        val asset = requireNotNull(loader.createInstancedAsset(buffer, instances)) { "Invalid model $path" }
        try { resources.loadResources(asset); asset.releaseSourceData() }
        catch (e: Exception) { loader.destroyAsset(asset); throw e }
        models[path] = Model(asset, instances.filterNotNull(),
            path == "decision-stage.glb" || path == "option-pad.glb" || path == "option-default.glb").also { tint(it) }
        fallbackPaths.remove(path)
        invalidateModels()
        if (path == "decision-stage.glb") {
            viewer.scene.addEntities(asset.entities.filter { asset.getName(it) !in setOf("DrawButton", "ActionBase", "ActionCap") }.toIntArray())
            deckEntity = asset.entities.firstOrNull { asset.getName(it) == "Turntable" }
            deckTransform = deckEntity?.let { viewer.engine.transformManager.getTransform(viewer.engine.transformManager.getInstance(it), null) }
        }
    }
    private fun tint(model: Model) {
        val colors = colors ?: return
        for (instance in model.instances) for (material in instance.materialInstances) {
            // 盘面、底座、指针与默认模型使用应用色板，食物继续使用原有材质颜色。
            val color = if (model.isSurface) surfaceColors[material.name] else null
            val resolved = color ?: colors.optJSONObject(material.name)?.optString(if (darkTheme) "dark" else "light")
                ?.let { Color(android.graphics.Color.parseColor(it)) } ?: continue
            material.setParameter("baseColorFactor", Colors.RgbaType.SRGB,
                resolved.red, resolved.green, resolved.blue, 1f)
            if (model.isSurface && color != null) material.setParameter("roughnessFactor", .9f)
        }
    }
    fun theme(catalog: org.json.JSONObject, dark: Boolean, background: Color, surfaceColors: Map<String, Color>) {
        if (released) return
        colors = catalog.getJSONObject("materials"); darkTheme = dark
        this.surfaceColors = surfaceColors
        models.values.forEach(::tint)
        viewer.renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true; clearColor = doubleArrayOf(background.red.toDouble(), background.green.toDouble(), background.blue.toDouble(), 1.0)
        }
        renderFrames = 8
    }
    fun beginLoading() { sceneReady = false; fallbackPaths.clear(); hideSurface() }
    private fun hideSurface() {
        alpha = 0f
        firstFrameFence?.let { viewer.engine.destroyFence(it) }; firstFrameFence = null
        revealOnNextFrame = false
        readyNotificationPending = sceneReady
        lastCursor = Double.NaN; renderFrames = 8
        onLoading()
    }
    fun ready() { sceneReady = true; readyNotificationPending = true; renderFrames = 8 }
    private fun revealCompletedFrame() {
        if (revealOnNextFrame) {
            // 首帧完成后再经过一次显示刷新，让加载层与有效画面交接。
            revealOnNextFrame = false; readyNotificationPending = false
            alpha = 1f
            onLoaded()
            return
        }
        val fence = firstFrameFence ?: return
        // timeout=0 只查询完成状态，不在 UI 线程等待 GPU。
        when (fence.wait(Fence.Mode.FLUSH, 0)) {
            Fence.FenceStatus.TIMEOUT_EXPIRED -> Unit
            Fence.FenceStatus.CONDITION_SATISFIED -> {
                viewer.engine.destroyFence(fence); firstFrameFence = null
                revealOnNextFrame = true
            }
            Fence.FenceStatus.ERROR -> {
                viewer.engine.destroyFence(fence); firstFrameFence = null
                readyNotificationPending = false
                onFailure(IllegalStateException("Unable to complete the first scene frame"))
            }
        }
    }
    private fun position(cursor: Double) {
        // 盘面与物品共用角度，静态底座和指针保持固定。
        deckEntity?.let { entity -> deckTransform?.let { original ->
            original.copyInto(deckRotation)
            val degrees = (-cursor * 360.0 / min(paths.size, Turntable.LIMIT).coerceAtLeast(1)).toFloat()
            Matrix.rotateM(deckRotation, 0, degrees, 0f, 1f, 0f)
            viewer.engine.transformManager.setTransform(viewer.engine.transformManager.getInstance(entity), deckRotation)
        } }
        val desired = mutableSetOf<FilamentInstance>()
        val used = mutableMapOf<String, Int>()
        for (p in Turntable.placements(paths.size, cursor)) {
            if (p.scale < .005) continue
            val path = if (paths[p.index] in fallbackPaths) "option-default.glb" else paths[p.index]
            val ordinal = used.getOrDefault(path, 0); used[path] = ordinal + 1
            val pad = models["option-pad.glb"]?.instances?.getOrNull(p.slot) ?: continue
            val model = models[path]?.instances?.getOrNull(ordinal)
            desired += pad
            if (model != null) desired += model
            for ((instance, y) in listOfNotNull(model?.let { it to .398f }, pad to .324f)) {
                Matrix.setIdentityM(transform, 0)
                Matrix.translateM(transform, 0, (sin(p.angle) * p.radius).toFloat(), y, (cos(p.angle) * p.radius).toFloat())
                Matrix.rotateM(transform, 0, Math.toDegrees(p.angle).toFloat(), 0f, 1f, 0f)
                Matrix.scaleM(transform, 0, p.scale.toFloat(), p.scale.toFloat(), p.scale.toFloat())
                viewer.engine.transformManager.setTransform(viewer.engine.transformManager.getInstance(instance.root), transform)
            }
        }
        for (instance in visible - desired) viewer.scene.removeEntities(instance.entities)
        for (instance in desired - visible) viewer.scene.addEntities(instance.entities)
        visible = desired
    }
    private fun fitCamera() {
        val viewport = viewer.view.viewport
        if (viewport.width <= 0 || viewport.height <= 0) return
        if (cameraWidth == viewport.width && cameraHeight == viewport.height) return
        cameraWidth = viewport.width; cameraHeight = viewport.height
        val aspect = viewport.width.toDouble() / viewport.height
        val halfHeight = max(3.6, 4.96 / aspect) / 2
        viewer.camera.setProjection(Camera.Projection.ORTHO, -halfHeight * aspect, halfHeight * aspect,
            -halfHeight, halfHeight, 0.1, 100.0)
        renderFrames = 8
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || released) return
        // 只降低 3D 缓冲分辨率，文字和按钮继续使用设备原生分辨率。
        val scale = min(1.0, 900.0 / max(w, h))
        uiHelper.setDesiredSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        cameraWidth = 0; renderFrames = 8
    }
    fun start() { if (!active && !released) { active = true; renderFrames = 8; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { active = false; Choreographer.getInstance().removeFrameCallback(this) }
    private fun releaseAssets() {
        if (released) return
        finishFirstFrameTrace()
        stop(); released = true
        firstFrameFence?.let { viewer.engine.destroyFence(it) }; firstFrameFence = null
        for (model in models.values) { for (instance in model.instances) viewer.scene.removeEntities(instance.entities); loader.destroyAsset(model.asset) }
        models.clear(); fallbackPaths.clear(); visible = emptySet()
        deckEntity = null; deckTransform = null
        viewer.scene.indirectLight?.let { viewer.scene.indirectLight = null; viewer.engine.destroyIndirectLight(it) }
        resources.destroy(); loader.destroy(); materials.destroyMaterials(); materials.destroy()
    }
    fun release() { releaseAssets(); if (!attached) viewer.destroy() }
    private fun finishFirstFrameTrace() {
        if (!firstFramePending) return
        firstFramePending = false
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("recoow.decision.firstFrame", traceCookie)
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!active || released) return
        if (isAttachedToWindow && windowVisibility == VISIBLE) {
            fitCamera()
            revealCompletedFrame()
            // 直接采样手势光标，不等待 Compose 整页重组；静止时保留已提交的帧。
            val cursor = readCursor()
            if (cursor != lastCursor) { position(cursor); lastCursor = cursor; renderFrames = 8 }
            val swapChain = viewer.swapChain
            // 这里没有 glTF 动画、相机手势或纹理解码任务，直接使用 Filament 的帧提交，
            // 避免 ModelViewer 面向动画播放器的额外排帧延迟和重复更新。
            if (renderFrames > 0 && uiHelper.isReadyToRender && swapChain != null && viewer.renderer.beginFrame(swapChain, frameTimeNanos)) {
                try { viewer.renderer.render(viewer.view) } finally { viewer.renderer.endFrame() }
                renderFrames--
                if (sceneReady && readyNotificationPending && firstFrameFence == null && !revealOnNextFrame) {
                    firstFrameFence = viewer.engine.createFence()
                }
                if (sceneReady) finishFirstFrameTrace()
                onFrame(frameTimeNanos)
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
