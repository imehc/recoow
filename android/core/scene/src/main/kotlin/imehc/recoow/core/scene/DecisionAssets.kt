package imehc.recoow.core.scene

import android.content.Context
import imehc.recoow.core.domain.decision.normalizedDecisionTitle
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Small shared source buffers; GPU assets belong to each surface and are released on detach. */
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
    fun path(catalog: JSONObject, title: String): String {
        val key = catalog.getJSONObject("aliases").optString(normalizedDecisionTitle(title), catalog.getString("fallback"))
        val assets = catalog.getJSONArray("assets")
        for (index in 0 until assets.length()) assets.getJSONObject(index).let { if (it.getString("key") == key) return it.getString("path") }
        return "option-default.glb"
    }
}
suspend fun prewarmDecisionScene(context: Context) {
    DecisionAssets.catalog(context)
    for (path in listOf("decision-stage.glb", "option-pad.glb", "option-default.glb")) DecisionAssets.bytes(context, path)
}
