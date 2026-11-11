package imehc.recoow.core.network

import kotlinx.serialization.json.*

/** HTTP 使用 snake_case；本地持久化信封与 Kotlin 模型保持原有命名。 */
internal object APIFields {
    private val legacyNames = apiFieldNames.entries.associate { (old, canonical) -> canonical to old }
    private val opaque = setOf("snapshot", "metadata", "before_value", "after_value", "contracts", "record_counts")
    fun name(value: String): String = apiFieldNames[value] ?: value

    fun rename(value: JsonElement, legacy: Boolean): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { rename(it, legacy) })
        is JsonObject -> {
            val result = linkedMapOf<String, JsonElement>()
            for ((key, item) in value) {
                val canonical = name(key)
                val target = if (legacy) legacyNames[canonical] ?: canonical else canonical
                require(target !in result) { "Duplicate API field" }
                result[target] = if (canonical in opaque) item else rename(item, legacy)
            }
            JsonObject(result)
        }
        else -> value
    }
}
