package imehc.recoow.core.network

import kotlinx.serialization.json.*

/** HTTP uses snake_case; durable local envelopes and Kotlin models keep their names. */
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
