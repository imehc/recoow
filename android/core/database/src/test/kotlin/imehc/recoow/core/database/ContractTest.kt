package imehc.recoow.core.database

import imehc.recoow.core.database.contract.ContractViolation
import imehc.recoow.core.database.generated.EntityContracts
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    private val bill = EntityContracts.registry.entity("bills")
    private val base = Json.parseToJsonElement("""{
        "id":"id","created_at":1,"updated_at":2,"deleted_at":null,"device_id":"device",
        "title":"bill","original_amount_cents":9007199254740993,"final_amount_cents":1,
        "transaction_type":"expense","category":"other","payment_method":"cash","occurred_at":2,
        "future":{"value":null}
    }""").jsonObject

    @Test fun olderContractDefaultsPreserveUnknownFieldsAndMissingVersusNull() {
        val result = bill.normalize(base, 1)
        assertEquals(9_007_199_254_740_993L, result.getValue("original_amount_cents").jsonPrimitive.long)
        assertEquals(0L, result.getValue("discount_amount_cents").jsonPrimitive.long)
        assertEquals("active", result.getValue("settlement_status").jsonPrimitive.content)
        assertEquals(base["future"], result["future"])
        assertFalse(result.containsKey("note"))
        assertEquals(JsonNull, result["deleted_at"])
    }

    @Test fun invalidNumbersEnumsAndVersionWindowsAreRejected() {
        for ((field, value) in listOf("original_amount_cents" to JsonPrimitive("1"),
            "original_amount_cents" to Json.parseToJsonElement("9223372036854775808"),
            "original_amount_cents" to JsonPrimitive(1.0), "payment_method" to JsonPrimitive("new-kind"))) {
            try { bill.normalize(JsonObject(base + (field to value)), 1); fail("Accepted $field=$value") } catch (_: ContractViolation) { }
        }
        try { bill.normalize(base, 99); fail() } catch (_: ContractViolation) { }
        try { EntityContracts.registry.entity("reminders").normalize(JsonObject(emptyMap()), 1); fail() } catch (_: ContractViolation) { }
        try { bill.normalize(base, 3); fail("Current version must supply required fields") } catch (_: ContractViolation) { }
    }
}
