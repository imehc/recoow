package imehc.recoow.core.data.backup

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import imehc.recoow.core.domain.navigation.ToolRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 只在 DatabaseHost 门禁内调用，使偏好改动与回滚边界一致。 */
internal class PortablePreferences(private val context: Context, private val store: DataStore<Preferences>) {
    suspend fun capture(): JSONObject {
        val values = store.data.first()
        val language = if (Build.VERSION.SDK_INT >= 33) when (context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language) {
            "zh" -> "simplifiedChinese"; "en" -> "english"; else -> "system"
        } else when (values[stringPreferencesKey("language")]) { "CHINESE" -> "simplifiedChinese"; "ENGLISH" -> "english"; else -> "system" }
        return JSONObject().put("languageRawValue", language)
            .put("appearanceRawValue", values[stringPreferencesKey("appearance")]?.lowercase() ?: "system")
            .put("addsPickedPhotosToMediaLibrary", values[booleanPreferencesKey("addsPickedPhotosToMediaLibrary")] ?: false)
            .put("savesCameraPhotosToLibrary", values[booleanPreferencesKey("savesCameraPhotosToLibrary")] ?: true)
            .put("hiddenToolRouteIDs", JSONArray(values[stringSetPreferencesKey("hiddenHomeTools")].orEmpty().sorted()))
    }
    suspend fun restore(value: JSONObject) {
        val language = when (value.optString("languageRawValue")) { "simplifiedChinese", "zh-Hans", "zh" -> "CHINESE"; "english", "en" -> "ENGLISH"; else -> "SYSTEM" }
        val appearance = value.optString("appearanceRawValue").uppercase().takeIf { it in setOf("SYSTEM", "LIGHT", "DARK") } ?: "SYSTEM"
        val hidden = value.getJSONArray("hiddenToolRouteIDs").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            .intersect(ToolRoute.entries.map { it.id }.toSet())
        store.edit { values ->
            values[stringPreferencesKey("language")] = language
            values[stringPreferencesKey("appearance")] = appearance
            values[booleanPreferencesKey("addsPickedPhotosToMediaLibrary")] = value.optBoolean("addsPickedPhotosToMediaLibrary", false)
            values[booleanPreferencesKey("savesCameraPhotosToLibrary")] = value.optBoolean("savesCameraPhotosToLibrary", true)
            values[stringSetPreferencesKey("hiddenHomeTools")] = hidden
        }
        if (Build.VERSION.SDK_INT >= 33) withContext(Dispatchers.Main.immediate) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(when (language) { "CHINESE" -> "zh-Hans"; "ENGLISH" -> "en"; else -> "" })
        }
    }
}
