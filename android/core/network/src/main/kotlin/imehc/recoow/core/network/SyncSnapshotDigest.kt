package imehc.recoow.core.network

import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** v1 includes all business/unknown fields and excludes only transport bookkeeping. */
object SyncSnapshotDigest {
    const val VERSION = 1
    val metadataFields = setOf("updated_at", "device_id", "server_version", "sync_status")

    fun digest(snapshot: JsonObject): String {
        val encoded = canonical(JsonObject(snapshot.filterKeys { it !in metadataFields }))
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonical(value: JsonElement): String = when (value) {
        JsonNull -> "z"
        is JsonObject -> {
            val keys = value.keys.sortedWith { first, second ->
                val a = first.toByteArray(Charsets.UTF_8); val b = second.toByteArray(Charsets.UTF_8)
                var difference = 0
                for (i in 0 until minOf(a.size, b.size)) {
                    difference = (a[i].toInt() and 255) - (b[i].toInt() and 255)
                    if (difference != 0) break
                }
                if (difference == 0) a.size - b.size else difference
            }
            "o${keys.size}{" + keys.joinToString("") { canonical(JsonPrimitive(it)) + canonical(value.getValue(it)) } + "}"
        }
        is JsonArray -> "a${value.size}[" + value.joinToString("") { canonical(it) } + "]"
        is JsonPrimitive -> when {
            value.isString -> "s${value.content.toByteArray(Charsets.UTF_8).size}:${value.content}"
            value.booleanOrNull != null -> if (value.boolean) "t" else "f"
            else -> {
                val decimal = BigDecimal(value.content).stripTrailingZeros()
                val number = if (decimal.signum() == 0) "0e0" else "${decimal.unscaledValue()}e${-decimal.scale().toLong()}"
                "n$number;"
            }
        }
    }
}
