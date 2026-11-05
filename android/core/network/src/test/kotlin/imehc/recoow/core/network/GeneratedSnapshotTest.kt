package imehc.recoow.core.network

import imehc.recoow.core.network.generated.models.RWAPIBillsRecord
import imehc.recoow.core.network.generated.models.RWAPIBillsSnapshot
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class GeneratedSnapshotTest {
    // The serializer must preserve wire presence even with common application JSON settings.
    private val json = Json { explicitNulls = false; encodeDefaults = false }
    private val source = Json.parseToJsonElement("""{
        "id":"bill", "created_at":1000, "updated_at":1000, "device_id":"device",
        "title":"账单", "original_amount_cents":2500, "discount_amount_cents":0,
        "final_amount_cents":2500, "transaction_type":"expense", "category":"food",
        "payment_method":"cash", "occurred_at":1000, "settlement_status":"active",
        "note":null, "image_data":"AQI=",
        "future":{"integer":9007199254740993,"values":[null,true,"text"]},
        "future_null":null, "additionalProperties":{"still":"a wire field"}
    }""").jsonObject

    @Test fun generatedBillRetainsUnknownIntegersNullsAndBase64Bytes() {
        val bill = json.decodeFromJsonElement<RWAPIBillsSnapshot>(source)
        assertEquals(9007199254740993L, bill.additionalProperties.getValue("future").jsonObject.getValue("integer").jsonPrimitive.long)
        assertEquals(source, json.encodeToJsonElement(bill))
        assertFalse(json.encodeToJsonElement(bill).jsonObject.containsKey("utility_account_number"))
    }

    @Test fun editingTypedFieldsKeepsUnknownFieldsAndIncludesNewOptionalValues() {
        val bill = json.decodeFromJsonElement<RWAPIBillsSnapshot>(source)
        val encoded = json.encodeToJsonElement(bill.copy(title = "修改后", utilityAccountNumber = "001234AB")).jsonObject
        assertEquals(JsonPrimitive("修改后"), encoded["title"])
        assertEquals(JsonPrimitive("001234AB"), encoded["utility_account_number"])
        assertEquals(source["future"], encoded["future"])
        assertEquals(JsonNull, encoded["note"])
        val withNumber = json.decodeFromJsonElement<RWAPIBillsSnapshot>(encoded)
        assertEquals(JsonNull, json.encodeToJsonElement(withNumber.copy(utilityAccountNumber = null)).jsonObject["utility_account_number"])
    }

    @Test fun resourceResponseUsesTheSnapshotSerializer() {
        val record = buildJsonObject {
            put("table", "bills"); put("id", "bill"); put("version", 7)
            put("updatedAtMs", 1000); put("deviceId", "device"); put("snapshot", source)
        }
        val decoded = json.decodeFromJsonElement<RWAPIBillsRecord>(record)
        assertEquals(source, json.encodeToJsonElement(decoded).jsonObject["snapshot"])
    }

    @Test fun missingRequiredFieldsAndUnknownFieldCollisionsAreRejected() {
        try {
            json.decodeFromJsonElement<RWAPIBillsSnapshot>(JsonObject(source - "title"))
            fail("Missing required title must fail")
        } catch (_: SerializationException) { }
        val bill = json.decodeFromJsonElement<RWAPIBillsSnapshot>(source)
        try {
            json.encodeToJsonElement(bill.copy(additionalProperties = mapOf("title" to JsonPrimitive("replace"))))
            fail("Extras cannot replace typed fields")
        } catch (_: IllegalArgumentException) { }
    }
}
