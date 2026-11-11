package imehc.recoow.core.network

import kotlinx.serialization.json.*

object SyncManifest {
    fun entries(page: JsonObject, generation: String, watermark: Long, after: Long): List<JsonObject> {
        require(page.text("generation") == generation && page.integer("watermark") == watermark)
        require(page.integer("digestVersion") == SyncSnapshotDigest.VERSION.toLong())
        val entries = page.getValue("entries").jsonArray.map { it.jsonObject }
        require(entries.size <= 500)
        var previous = after
        for (entry in entries) {
            val version = entry.integer("version")
            require(version > previous && version <= watermark)
            entry.text("table"); entry.text("id"); entry.text("deviceId")
            require(entry.integer("updatedAtMs") > 0 && entry.integer("dataVersion") > 0)
            val digest = entry.getValue("digest").jsonPrimitive
            require(digest.isString && (digest.content.isEmpty() || digest.content.matches(Regex("[0-9a-f]{64}"))))
            require(entry.getValue("metadata").jsonObject.keys.all { it in SyncSnapshotDigest.metadataFields })
            require(entry.text("operation") in setOf("insert", "update", "delete"))
            require(entry.flag("deleted") == (entry.text("operation") == "delete"))
            previous = version
        }
        require(page.integer("cursor") == previous && (!page.flag("more") || entries.isNotEmpty()))
        return entries
    }

    /** 返回普通引导分页，使收件箱与落库的持久化语义保持统一。 */
    fun hydrate(page: JsonObject, entries: List<JsonObject>, local: Map<Long, JsonObject>,
        mutations: Map<Long, String>, fetched: JsonObject?): JsonObject {
        val missing = entries.filter { it.integer("version") !in local }
        val snapshots = if (missing.isEmpty()) emptyList() else {
            requireNotNull(fetched)
            require(fetched.text("generation") == page.text("generation") && fetched.integer("watermark") == page.integer("watermark"))
            fetched.getValue("entries").jsonArray.map { it.jsonObject }.also { rows ->
                require(rows.size == missing.size)
                for ((row, expected) in rows.zip(missing)) {
                    val validated = SyncProtocol.row(row)
                    for (field in listOf("version", "table", "id")) require(validated[field] == expected[field])
                }
            }
        }
        val byVersion = snapshots.associateBy { it.integer("version") }
        return buildJsonObject {
            for ((key, value) in page) if (key != "digestVersion" && key != "entries") put(key, value)
            put("entries", JsonArray(entries.map { entry ->
                val version = entry.integer("version")
                byVersion[version] ?: run {
                    val snapshot = JsonObject(requireNotNull(local[version]).filterKeys { it !in SyncSnapshotDigest.metadataFields } + entry.getValue("metadata").jsonObject)
                    JsonObject(entry.filterKeys { it !in setOf("metadata", "digest") } + ("snapshot" to snapshot))
                        .also { SyncProtocol.row(it) }
                }
            }))
            put("matchedSeeds", buildJsonObject { for ((version, mutation) in mutations) put(version.toString(), mutation) })
        }
    }
}
