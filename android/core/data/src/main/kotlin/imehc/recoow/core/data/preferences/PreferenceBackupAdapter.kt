package imehc.recoow.core.data.preferences

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import imehc.recoow.core.backup.BackupPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 在 DatabaseHost 的独占门禁内调用；凭据永远不会写入该偏好存储。 */
class PreferenceBackupAdapter(private val context: Context, private val store: DataStore<Preferences>) : BackupPreferences {
    override suspend fun capture(): String {
        val values = JSONObject()
        store.data.first().asMap().forEach { (key, value) ->
            val kind = when (value) {
                is String -> "string"; is Long -> "long"; is Int -> "int"; is Boolean -> "bool"
                is Float -> "float"; is Double -> "double"; is Set<*> -> "strings"
                else -> error("Unsupported preference ${key.name}")
            }
            values.put(key.name, JSONObject().put("type", kind).put("value", if (value is Set<*>) JSONArray(value) else value))
        }
        val result = JSONObject().put("values", values)
        if (Build.VERSION.SDK_INT >= 33) result.put("locales", context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        return result.toString()
    }

    override suspend fun restore(json: String) {
        val data = JSONObject(json)
        val values = data.getJSONObject("values")
        store.edit { preferences ->
            preferences.clear()
            values.keys().forEach { key ->
                val entry = values.getJSONObject(key)
                when (entry.getString("type")) {
                    "string" -> preferences[stringPreferencesKey(key)] = entry.getString("value")
                    "long" -> preferences[longPreferencesKey(key)] = entry.getLong("value")
                    "int" -> preferences[intPreferencesKey(key)] = entry.getInt("value")
                    "bool" -> preferences[booleanPreferencesKey(key)] = entry.getBoolean("value")
                    "float" -> preferences[floatPreferencesKey(key)] = entry.getDouble("value").toFloat()
                    "double" -> preferences[doublePreferencesKey(key)] = entry.getDouble("value")
                    "strings" -> preferences[stringSetPreferencesKey(key)] = entry.getJSONArray("value").let { array ->
                        (0 until array.length()).map { array.getString(it) }.toSet()
                    }
                    else -> error("Unsupported preference $key")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && data.has("locales")) withContext(Dispatchers.Main.immediate) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(data.getString("locales"))
        }
    }
}
