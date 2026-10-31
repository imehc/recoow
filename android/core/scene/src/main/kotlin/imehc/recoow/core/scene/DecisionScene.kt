package imehc.recoow.core.scene

import android.content.Context
import android.opengl.Matrix
import android.os.Build
import android.os.Trace
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.Utils
import imehc.recoow.core.domain.decision.Turntable
import java.nio.ByteBuffer
import kotlin.math.*
import kotlinx.coroutines.CancellationException

@Composable
fun DecisionScene(titles: List<String>, cursor: Double, dark: Boolean, modifier: Modifier = Modifier,
    onLoaded: () -> Unit = {}, onFailure: (Throwable) -> Unit, onFrame: (Long) -> Unit = {}) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    val creation = remember(context) { runCatching { DecisionSurface(context) } }
    val surface = creation.getOrNull(); val failed by rememberUpdatedState(onFailure); val loaded by rememberUpdatedState(onLoaded)
    val frame by rememberUpdatedState(onFrame)
    if (surface == null) { LaunchedEffect(creation) { failed(creation.exceptionOrNull()!!) }; return }
    SideEffect { surface.cursor = cursor; surface.onFrame = { frame(it) } }
    DisposableEffect(surface, owner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = surface.start()
            override fun onPause(owner: LifecycleOwner) = surface.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); surface.stop() }
    }
    LaunchedEffect(surface, titles, dark) {
        try {
            val catalog = DecisionAssets.catalog(context)
            surface.load("decision-stage.glb", DecisionAssets.bytes(context, "decision-stage.glb"), 1)
            surface.load("option-pad.glb", DecisionAssets.bytes(context, "option-pad.glb"), Turntable.LIMIT)
            surface.load("option-default.glb", DecisionAssets.bytes(context, "option-default.glb"), Turntable.LIMIT)
            val paths = titles.map { DecisionAssets.path(catalog, it) }
            for (path in paths.distinct()) {
                try { surface.load(path, DecisionAssets.bytes(context, path), Turntable.LIMIT) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { /* The shared default remains usable. */ }
            }
            surface.paths = paths
            surface.theme(catalog, dark)
            loaded()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) }
    }
    AndroidView(factory = { surface }, modifier = modifier, onReset = null, onRelease = { it.release() })
}

private class DecisionSurface(context: Context) : SurfaceView(context), Choreographer.FrameCallback {
    private val viewer: ModelViewer
    private val materials: UbershaderProvider
    private val loader: AssetLoader
    private val resources: ResourceLoader
    private data class Model(val asset: FilamentAsset, val instances: List<FilamentInstance>)
    private val models = mutableMapOf<String, Model>()
    private var visible = emptySet<FilamentInstance>()
    var paths: List<String> = emptyList()
    var cursor = 0.0
    var onFrame: (Long) -> Unit = {}
    private var active = false; private var attached = false; private var released = false
    private var sceneReady = false
    private var firstFramePending = true
    private val traceCookie = System.identityHashCode(this)
    init {
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection("recoow.decision.firstFrame", traceCookie)
        Utils.init()
        // Registered before ModelViewer's detach listener, so our assets die before its engine.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attached = true }
            override fun onViewDetachedFromWindow(v: View) { releaseAssets() }
        })
        viewer = ModelViewer(this, manipulator = Manipulator.Builder().targetPosition(0f, .3f, 0f).orbitHomePosition(0f, 6f, 8f).build(Manipulator.Mode.ORBIT))
        viewer.autoPlayAnimations = false
        viewer.cameraFocalLength = 35f
        viewer.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true }
        materials = UbershaderProvider(viewer.engine)
        loader = AssetLoader(viewer.engine, materials, EntityManager.get())
        resources = ResourceLoader(viewer.engine)
        viewer.scene.indirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(.7f, .7f, .7f)).intensity(20_000f).build(viewer.engine)
        setOnTouchListener { _, _ -> false }
    }
    fun load(path: String, buffer: ByteBuffer, count: Int) {
        if (released || path in models) return
        val instances = arrayOfNulls<FilamentInstance>(count)
        val asset = requireNotNull(loader.createInstancedAsset(buffer, instances)) { "Invalid model $path" }
        try { resources.loadResources(asset); asset.releaseSourceData() }
        catch (e: Exception) { loader.destroyAsset(asset); throw e }
        models[path] = Model(asset, instances.filterNotNull())
        if (path == "decision-stage.glb") viewer.scene.addEntities(asset.entities)
    }
    fun theme(catalog: org.json.JSONObject, dark: Boolean) {
        if (released) return
        val colors = catalog.getJSONObject("materials")
        for (model in models.values) for (instance in model.instances) for (material in instance.materialInstances) {
            val color = colors.optJSONObject(material.name)?.optString(if (dark) "dark" else "light") ?: continue
            val c = android.graphics.Color.parseColor(color)
            material.setParameter("baseColorFactor", Colors.RgbaType.SRGB,
                android.graphics.Color.red(c) / 255f, android.graphics.Color.green(c) / 255f, android.graphics.Color.blue(c) / 255f, 1f)
        }
        viewer.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = if (dark) doubleArrayOf(.016, .016, .021, 1.0) else doubleArrayOf(.96, .95, .98, 1.0) }
        sceneReady = true
    }
    private fun position() {
        val desired = mutableSetOf<FilamentInstance>()
        for (p in Turntable.placements(paths.size, cursor)) {
            val model = (models[paths[p.index]] ?: models["option-default.glb"])?.instances?.getOrNull(p.slot) ?: continue
            val pad = models["option-pad.glb"]?.instances?.getOrNull(p.slot) ?: continue
            if (p.scale < .005) continue
            desired += model; desired += pad
            for ((instance, y) in listOf(model to .398f, pad to .324f)) {
                val transform = FloatArray(16); Matrix.setIdentityM(transform, 0)
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
    fun start() { if (!active && !released) { active = true; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { active = false; Choreographer.getInstance().removeFrameCallback(this) }
    private fun releaseAssets() {
        if (released) return
        finishFirstFrameTrace()
        stop(); released = true
        for (model in models.values) { for (instance in model.instances) viewer.scene.removeEntities(instance.entities); loader.destroyAsset(model.asset) }
        models.clear(); visible = emptySet()
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
            position()
            if (viewer.render(frameTimeNanos)) {
                // Measure through the first submitted frame containing the loaded scene.
                if (sceneReady) finishFirstFrameTrace()
                onFrame(frameTimeNanos)
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
