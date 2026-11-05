package imehc.recoow.core.sync

import imehc.recoow.core.database.generated.EntityContracts
import kotlinx.serialization.json.*

/** Applies only later local edits to the acknowledged snapshot; int64 values stay JSON numbers. */
internal object SyncSnapshotRebase {
    private val settlement = setOf("original_amount_cents", "discount_amount_cents", "final_amount_cents",
        "transaction_type", "category", "payment_method", "stored_value_account_id", "settlement_status",
        "group_buy_valid_until", "redeemed_at", "refund_reason")

    fun conflicts(table: String, base: JsonObject, local: JsonObject, remote: JsonObject): Boolean {
        fun deleted(fields: JsonObject) = fields["deleted_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.long?.let { it > 0 } == true
        if (deleted(base) != deleted(local) || deleted(base) != deleted(remote)) return true
        val ignored = EntityContracts.registry.entity(table).fields.filter { it.binary || it.derived }.map { it.name }.toSet() +
            setOf("id", "created_at", "updated_at", "device_id", "deleted_at", "server_version", "sync_status", "stored_value_effects")
        val changed = local.keys.filter { it !in ignored && local[it] != base[it] }.toMutableSet()
        if (table == "bills" && changed.any { it in settlement }) {
            if (settlement.any { remote[it] != base[it] } && settlement.any { remote[it] != local[it] }) return true
            changed.removeAll(settlement)
        }
        return changed.any { remote[it] != base[it] && local[it] != remote[it] }
    }

    fun snapshot(table: String, base: JsonObject, local: JsonObject, remote: JsonObject): JsonObject {
        val ignored = EntityContracts.registry.entity(table).fields.filter { it.binary || it.derived }.map { it.name }.toSet() +
            setOf("id", "created_at", "server_version", "sync_status", "stored_value_effects")
        val changed = local.keys.filter { it !in ignored && local[it] != base[it] }.toMutableSet()
        if (table == "bills" && changed.any { it in settlement }) changed.addAll(settlement)
        val result = remote.toMutableMap()
        for (field in changed) local[field]?.let { result[field] = it } ?: result.remove(field)
        for (field in listOf("updated_at", "device_id", "deleted_at")) local[field]?.let { result[field] = it } ?: result.remove(field)
        if (table == "bills") {
            val effects = remote["stored_value_effects"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                ?.let { Json.parseToJsonElement(it).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.long }.toMutableMap() } ?: mutableMapOf()
            val timestamp = local.getValue("updated_at").jsonPrimitive.long
            for ((fields, subtract) in listOf(remote to true, JsonObject(result) to false)) {
                val id = fields["stored_value_account_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: continue
                if (id.isEmpty()) continue
                val delta = balanceDelta(fields, timestamp).let { if (subtract) Math.negateExact(it) else it }
                val total = Math.addExact(effects[id] ?: 0, delta)
                if (total == 0L) effects.remove(id) else effects[id] = total
            }
            result["stored_value_effects"] = JsonPrimitive(buildJsonObject { effects.toSortedMap().forEach { (id, value) -> put(id, value) } }.toString())
        }
        return JsonObject(result)
    }

    private fun balanceDelta(fields: JsonObject, timestamp: Long): Long {
        fun text(key: String) = fields[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        fun number(key: String) = fields[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.long
        if (number("deleted_at") != null || text("settlement_status") == "refunded") return 0
        val type = text("transaction_type")
        if (type == "expense" && text("category") == "groupBuy" && text("settlement_status") == "active" &&
            number("group_buy_valid_until")?.let { it < timestamp } == true) return 0
        return when (type) { "expense" -> number("original_amount_cents") ?: 0
            "storedValueUse" -> Math.negateExact(number("final_amount_cents") ?: 0); else -> 0 }
    }
}
