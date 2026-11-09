package imehc.recoow.core.network

import kotlinx.serialization.json.*

fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString && it.content.isNotBlank()) { "Invalid $key" }; it.content
}
fun JsonObject.integer(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString && it.content.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Invalid $key" }
    requireNotNull(it.longOrNull) { "Invalid $key" }
}
fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.let {
    require(!it.isString) { "Invalid $key" }; requireNotNull(it.booleanOrNull) { "Invalid $key" }
}
fun decodeObject(bytes: ByteArray): JsonObject = APIFields.rename(
    Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)), true).jsonObject

data class Capabilities(val account: String, val generation: String, val head: Long, val minCursor: Long,
    val pushLimit: Int, val pullLimit: Int, val mediaLimit: Long, val manifestBootstrap: Boolean = false, val mutationOrigin: Boolean = false, val deviceProfiles: Boolean = false,
    val accountCredentials: Boolean = false) {
    companion object {
        fun decode(raw: JsonObject, account: String, contracts: Map<String, Int>): Capabilities {
            require(raw.integer("protocolVersion") == 2L && raw.text("accountId") == account)
            val versions = raw.getValue("contracts").jsonObject
            require(versions.keys == contracts.keys && contracts.all { (table, version) -> versions.integer(table) == version.toLong() })
            val features = raw.getValue("features").jsonArray.map { it.jsonPrimitive.content }.toSet()
            require(features.containsAll(setOf("revision", "receipts", "bootstrap", "media", "unknown_fields", "projections")))
            val dataset = raw.getValue("dataset").jsonObject
            val head = dataset.integer("head"); val min = dataset.integer("minCursor")
            require(head >= min && min >= 0)
            val limits = raw.getValue("limits").jsonObject
            val push = limits.integer("pushItems"); val pull = limits.integer("pullEntries"); val media = limits.integer("mediaBytes")
            require(push in 1..1000 && pull in 1..2000 && media in 1..200L * 1024 * 1024)
            return Capabilities(account, dataset.text("generation"), head, min, push.toInt(), pull.toInt(), media,
                "manifest_bootstrap_v1" in features, "mutation_origin_v1" in features, "device_profiles_v1" in features, "account_credentials_v1" in features)
        }
    }
}

data class SyncPage(val entries: List<JsonObject>, val cursor: Long, val more: Boolean)
data class Receipt(val mutation: String, val status: String, val version: Long, val current: JsonObject?)

object SyncProtocol {
    /** Normalize zero deletion timestamps before the persistence layer sees an envelope. */
    fun row(raw: JsonObject): JsonObject {
        val table = raw.text("table"); val id = raw.text("id"); val device = raw.text("deviceId")
        val version = raw.integer("version"); val updated = raw.integer("updatedAtMs")
        val deleted = raw["deletedAtMs"]?.takeIf { it != JsonNull }?.let { raw.integer("deletedAtMs") } ?: 0
        require(version > 0 && updated > 0 && (deleted == 0L || deleted == updated))
        val dataVersion = if ("dataVersion" in raw) raw.integer("dataVersion") else 1
        require(dataVersion in 1..Int.MAX_VALUE)
        if (deleted == 0L) {
            val snapshot = raw.getValue("snapshot").jsonObject
            if ("id" in snapshot) require(snapshot.text("id") == id)
            if ("device_id" in snapshot) require(snapshot.text("device_id") == device)
            if ("updated_at" in snapshot) require(snapshot.integer("updated_at") == updated)
            require(snapshot["deleted_at"] == null || snapshot["deleted_at"] == JsonNull)
        }
        return buildJsonObject {
            put("table", table); put("id", id); put("deviceId", device); put("version", version)
            put("updatedAtMs", updated); put("dataVersion", dataVersion)
            put("deletedAtMs", if (deleted > 0) JsonPrimitive(deleted) else JsonNull)
            put("snapshot", raw["snapshot"] ?: JsonNull)
        }
    }

    fun page(raw: JsonObject, generation: String, after: Long, watermark: Long? = null): SyncPage {
        require(raw.text("generation") == generation)
        val cursor = raw.integer("cursor"); val more = raw.flag("more")
        var previous = after
        val entries = raw.getValue("entries").jsonArray.map {
            val change = it.jsonObject; val row = row(change)
            val deleted = row["deletedAtMs"] != JsonNull
            require(change.text("operation") in setOf("insert", "update", "delete"))
            require(change.flag("deleted") == deleted && (change.text("operation") == "delete") == deleted)
            require(row.integer("version") > previous)
            previous = row.integer("version")
            row
        }
        require(!more || entries.isNotEmpty())
        if (watermark != null) {
            require(raw.integer("watermark") == watermark && previous <= watermark)
            require(cursor == previous)
        } else require(cursor == previous)
        require(cursor >= after)
        return SyncPage(entries, cursor, more)
    }

    fun receipts(raw: JsonObject, requests: List<JsonObject>): List<Receipt> {
        val receipts = raw.getValue("results").jsonArray
        require(receipts.size == requests.size)
        return receipts.zip(requests).map { (element, request) ->
            val receipt = element.jsonObject
            for (key in listOf("table", "id", "operation", "mutationId")) require(receipt.text(key) == request.text(key))
            val status = receipt.text("status"); val version = receipt.integer("version")
            require(status in setOf("applied", "duplicate", "conflict", "unsupported_table", "invalid_operation", "mutation_reused", "dependency_missing", "invalid_revision"))
            require(version >= 0 && receipt.flag("applied") == (status == "applied"))
            val current = receipt["current"]?.takeIf { it != JsonNull }?.jsonObject?.let(::row)
            if (current != null) require(current.text("table") == request.text("table") && current.text("id") == request.text("id") && current.integer("version") == version)
            if (status in setOf("applied", "duplicate", "conflict")) require(version > 0 && current != null)
            Receipt(receipt.text("mutationId"), status, version, current)
        }
    }
}
