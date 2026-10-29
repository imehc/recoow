package imehc.recoow.core.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncProtocolTest {
    private fun objectOf(value: String) = Json.parseToJsonElement(value).jsonObject
    private val row = objectOf("""{"table":"bills","id":"a","version":7,"updatedAtMs":1700000000000,"deviceId":"d","dataVersion":3,"deletedAtMs":0,"snapshot":{"id":"a","device_id":"d","updated_at":1700000000000,"deleted_at":null,"future":{"exact":9007199254740993}}}""")
    private fun JsonObject.change(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun rowKeepsUnknownFieldsAndExactIntegersButRejectsInconsistentMetadata() {
        assertEquals(9007199254740993L, SyncProtocol.row(row)["snapshot"]!!.jsonObject["future"]!!.jsonObject.integer("exact"))
        assertEquals(JsonNull, SyncProtocol.row(row)["deletedAtMs"])
        rejected { SyncProtocol.row(row.change("version", JsonPrimitive("7"))) }
        rejected { SyncProtocol.row(row.change("version", JsonPrimitive(7.0))) }
        rejected { SyncProtocol.row(row.change("deviceId", JsonPrimitive("different"))) }
        rejected { SyncProtocol.row(row.change("deletedAtMs", JsonPrimitive(1))) }
    }
    @Test fun pagesValidateGenerationFixedWatermarkOrderingAndProgress() {
        val change = row.change("operation", JsonPrimitive("update")).change("deleted", JsonPrimitive(false))
        val page = buildJsonObject { put("entries", JsonArray(listOf(change))); put("cursor", 7); put("more", false); put("generation", "g"); put("watermark", 10) }
        assertEquals(7L, SyncProtocol.page(page, "g", 0, 10).cursor)
        rejected { SyncProtocol.page(page, "other", 0, 10) }
        rejected { SyncProtocol.page(page, "g", 0, 11) }
        rejected { SyncProtocol.page(page.change("cursor", JsonPrimitive(8)), "g", 0) }
        rejected { SyncProtocol.page(page.change("entries", JsonArray(listOf(change, change))), "g", 0) }
        rejected { SyncProtocol.page(page.change("entries", JsonArray(emptyList())).change("more", JsonPrimitive(true)), "g", 7) }
    }
    @Test fun receiptsBindEveryMutationAndRequireCanonicalRows() {
        val request = objectOf("""{"mutationId":"m","table":"bills","id":"a","operation":"update"}""")
        val receipt = JsonObject(request + mapOf("status" to JsonPrimitive("applied"), "version" to JsonPrimitive(7), "applied" to JsonPrimitive(true), "current" to row))
        fun decode(value: JsonObject) = SyncProtocol.receipts(buildJsonObject { put("results", JsonArray(listOf(value))) }, listOf(request))
        assertEquals("applied", decode(receipt).single().status)
        rejected { decode(receipt.change("mutationId", JsonPrimitive("different"))) }
        rejected { decode(receipt.change("current", JsonNull)) }
        rejected { decode(receipt.change("applied", JsonPrimitive(false))) }
        rejected { decode(receipt.change("version", JsonPrimitive(8))) }
    }
    @Test fun transportRejectsCredentialUrlsPublicHttpAndFragments() {
        val factory = SyncApiFactory()
        assertEquals("https://example.com/recoow/", factory.endpoint("https://example.com/recoow"))
        rejected { factory.endpoint("http://example.com") }
        rejected { factory.endpoint("https://user:secret@example.com") }
        rejected { factory.endpoint("https://example.com#fragment") }
        rejected { factory.endpoint("http://127.0.0.1") }
        assertEquals("http://10.0.2.2:8080/", SyncApiFactory(true).endpoint("http://10.0.2.2:8080"))
    }
}
