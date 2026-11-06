package imehc.recoow.core.network

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncManifestTest {
    @Test fun digestMatchesSharedExactNumberAndUnicodeVectors() {
        val path = listOf(File("../../../tests/fixtures/sync-digest-v1.json"), File("../tests/fixtures/sync-digest-v1.json"))
            .first { it.isFile }
        for (item in Json.parseToJsonElement(path.readText()).jsonArray) {
            val vector = item.jsonObject
            assertEquals(vector.text("digest"), SyncSnapshotDigest.digest(Json.parseToJsonElement(vector.text("snapshot")).jsonObject))
        }
    }

    @Test fun hydratesOnlyMissingContentAndRestoresRemoteMetadata() {
        val local = Json.parseToJsonElement("""{"id":"same","name":"value","updated_at":5,"device_id":"local","deleted_at":null}""").jsonObject
        fun entry(id: String, version: Long, digest: String) = buildJsonObject {
            put("table","diary_tags");put("id",id);put("version",version);put("updatedAtMs",10);put("deviceId","remote")
            put("dataVersion",1);put("deleted",false);put("operation","update");put("digest",digest)
            put("metadata", buildJsonObject {put("updated_at",10);put("device_id","remote")})
        }
        val page = buildJsonObject {
            put("generation","g");put("watermark",3);put("cursor",3);put("more",false);put("digestVersion",1)
            put("entries",JsonArray(listOf(entry("same",1,SyncSnapshotDigest.digest(local)),entry("missing",3,""))))
        }
        val entries = SyncManifest.entries(page,"g",3,0)
        val remote = buildJsonObject {
            put("generation","g");put("watermark",3)
            put("entries", JsonArray(listOf(JsonObject(entries[1].filterKeys { it !in setOf("digest","metadata") } +
                ("snapshot" to JsonObject(local + ("id" to JsonPrimitive("missing")) + ("updated_at" to JsonPrimitive(10)) + ("device_id" to JsonPrimitive("remote"))))))))
        }
        val hydrated = SyncManifest.hydrate(page,entries,mapOf(1L to local),mapOf(1L to "seed"),remote)
        val parsed = SyncProtocol.page(hydrated,"g",0,3)
        assertEquals(2,parsed.entries.size)
        assertEquals("remote",parsed.entries[0].getValue("snapshot").jsonObject.text("device_id"))
        assertEquals(10L,parsed.entries[0].getValue("snapshot").jsonObject.integer("updated_at"))
        assertEquals("seed",hydrated.getValue("matchedSeeds").jsonObject.text("1"))
    }

    @Test fun changedUnknownFieldNullAndLargeIntegerNeverMatch() {
        val values = listOf("""{"amount":9007199254740993,"optional":null}""", """{"amount":9007199254740992,"optional":null}""",
            """{"amount":9007199254740993}""", """{"amount":9007199254740993,"optional":null,"future":1}""")
        assertEquals(values.size,values.map { SyncSnapshotDigest.digest(Json.parseToJsonElement(it).jsonObject) }.toSet().size)
    }
}
