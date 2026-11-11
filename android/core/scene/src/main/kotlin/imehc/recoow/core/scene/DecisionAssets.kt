package imehc.recoow.core.scene

import android.content.Context
import imehc.recoow.core.domain.decision.normalizedDecisionTitle
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 只共享小型源缓冲；GPU 资源归各渲染表面所有，在分离时释放。 */
internal object DecisionAssets {
    private val lock = Mutex()
    private val buffers = mutableMapOf<String, ByteArray>()
    private var manifest: JSONObject? = null
    suspend fun catalog(context: Context): JSONObject = lock.withLock {
        manifest ?: withContext(Dispatchers.IO) { JSONObject(context.assets.open("decision/asset-manifest.json").bufferedReader().use { it.readText() }) }.also { manifest = it }
    }
    suspend fun bytes(context: Context, path: String): ByteBuffer = lock.withLock {
        ByteBuffer.wrap(buffers[path] ?: withContext(Dispatchers.IO) { context.assets.open("decision/$path").use { it.readBytes() } }.also { buffers[path] = it })
    }
    fun path(catalog: JSONObject, title: String, modelKey: String? = null): String {
        val assets = catalog.getJSONArray("assets")
        if (modelKey != null) for (index in 0 until assets.length()) assets.getJSONObject(index).let {
            if (it.getString("key") == modelKey) return it.getString("path")
        }
        val key = catalog.getJSONObject("aliases").optString(normalizedDecisionTitle(title), catalog.getString("fallback"))
        for (index in 0 until assets.length()) assets.getJSONObject(index).let { if (it.getString("key") == key) return it.getString("path") }
        return "option-default.glb"
    }
}

/** 预览使用实际打包的目录，不创建集合或候选项。 */
data class DecisionModelEntry(val key: String)
suspend fun decisionModelCatalog(context: Context): List<DecisionModelEntry> {
    val assets = DecisionAssets.catalog(context).getJSONArray("assets")
    return (0 until assets.length()).map { DecisionModelEntry(assets.getJSONObject(it).getString("key")) }
}
suspend fun prewarmDecisionScene(context: Context) {
    val catalog = DecisionAssets.catalog(context)
    val assets = catalog.getJSONArray("assets")
    val paths = listOf("decision-stage.glb", "option-pad.glb") + (0 until assets.length()).map { assets.getJSONObject(it).getString("path") }
    for (path in paths) DecisionAssets.bytes(context, path)
}
