package imehc.recoow.core.database.contract

import kotlinx.serialization.json.*

class ContractViolation(message: String) : IllegalArgumentException(message)

data class FieldContract(val name: String, val type: String, val required: Boolean, val nullable: Boolean,
    val binary: Boolean, val derived: Boolean, val default: JsonElement?, val enumValues: Set<String>, val jsonText: Boolean,
    val labels: Map<String, String> = emptyMap())
data class Dependency(val field: String, val table: String)
data class EntityContract(val table: String, val version: Int, val minimumVersion: Int,
    val fields: List<FieldContract>, val dependencies: List<Dependency>) {
    fun normalize(snapshot: JsonObject, dataVersion: Int): JsonObject {
        if (dataVersion !in minimumVersion..version) throw ContractViolation("Unsupported $table dataVersion $dataVersion")
        val result = snapshot.toMutableMap()
        for (field in fields) {
            if (field.derived) { result.remove(field.name); continue }
            var value = result[field.name]
            if (field.binary) {
                if (value != null && value != JsonNull) throw ContractViolation("$table.${field.name} must use binary storage")
                result.remove(field.name)
                continue
            }
            if (field.name == "server_version") { result.remove(field.name); continue }
            if (value == null && dataVersion < version && field.default != null) {
                value = field.default
                result[field.name] = value
            }
            if (value == null) {
                if (field.required) throw ContractViolation("Missing $table.${field.name}")
                continue
            }
            if (value == JsonNull) {
                if (!field.nullable) throw ContractViolation("Null $table.${field.name}")
                continue
            }
            val primitive = value as? JsonPrimitive ?: throw ContractViolation("Invalid $table.${field.name}")
            val valid = when (field.type) {
                "text" -> primitive.isString && (field.enumValues.isEmpty() || primitive.content in field.enumValues)
                "int" -> !primitive.isString && primitive.content.matches(Regex("-?(0|[1-9][0-9]*)")) && primitive.longOrNull != null
                "real" -> !primitive.isString && primitive.doubleOrNull?.isFinite() == true
                "bool" -> !primitive.isString && primitive.booleanOrNull != null
                else -> false
            }
            if (!valid) throw ContractViolation("Invalid $table.${field.name}")
            if (field.jsonText) try { Json.parseToJsonElement(primitive.content) } catch (_: IllegalArgumentException) {
                throw ContractViolation("Invalid JSON text $table.${field.name}")
            }
        }
        result.remove("sync_status")
        return JsonObject(result)
    }
}

class ContractRegistry private constructor(val entities: Map<String, EntityContract>) {
    fun entity(table: String): EntityContract = entities[table] ?: throw ContractViolation("Unsupported entity $table")

    companion object {
        fun parse(json: String): ContractRegistry {
            val entities = Json.parseToJsonElement(json).jsonArray.map { element ->
                val row = element.jsonObject
                fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
                EntityContract(row.getValue("table").jsonPrimitive.content,
                    row.getValue("dataVersion").jsonPrimitive.int, row.getValue("minDataVersion").jsonPrimitive.int,
                    row.getValue("columns").jsonArray.map { column ->
                        val f = column.jsonObject
                        FieldContract(f.getValue("name").jsonPrimitive.content, f.getValue("type").jsonPrimitive.content,
                            f.flag("required"), f.flag("nullable"), f.flag("binary"), f.flag("derived"), f["default"],
                            f["enumValues"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                            f["format"]?.jsonPrimitive?.content == "json",
                            f["labels"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty())
                    }, row.getValue("dependencies").jsonArray.map { dependency ->
                        val d = dependency.jsonObject
                        Dependency(d.getValue("field").jsonPrimitive.content, d.getValue("table").jsonPrimitive.content)
                    })
            }
            return ContractRegistry(entities.associateBy { it.table })
        }
    }
}
