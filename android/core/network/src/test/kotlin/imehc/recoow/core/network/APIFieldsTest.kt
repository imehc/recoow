package imehc.recoow.core.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class APIFieldsTest {
    @Test fun canonicalResponsePreservesSnapshotsAndInt64Values() {
        val bytes = """{"account_id":"a","origin":{"device_id":"d"},"snapshot":{"deviceId":"business","amount":9007199254740993,"future":null},"record_counts":{"diary_entries":1}}""".encodeToByteArray()
        val decoded = decodeObject(bytes)
        assertEquals("a", decoded.text("accountId"))
        assertEquals("d", decoded["origin"]!!.jsonObject.text("deviceId"))
        assertEquals("business", decoded["snapshot"]!!.jsonObject.text("deviceId"))
        assertEquals(9007199254740993L, decoded["snapshot"]!!.jsonObject.integer("amount"))
        assertEquals(JsonNull, decoded["snapshot"]!!.jsonObject["future"])
        assertEquals(JsonPrimitive(1), decoded["recordCounts"]!!.jsonObject["diary_entries"])
        val outgoing = APIFields.rename(decoded, false).jsonObject
        assertEquals(decoded["snapshot"],outgoing["snapshot"])
        assertTrue("account_id" in outgoing && "accountId" !in outgoing)
    }

    @Test fun oldResponsesRemainReadableAndMixedAliasesFail() {
        assertEquals("a",decodeObject("""{"accountId":"a"}""".encodeToByteArray()).text("accountId"))
        try {
            decodeObject("""{"accountId":"a","account_id":"b"}""".encodeToByteArray())
            fail("Alias collisions must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
