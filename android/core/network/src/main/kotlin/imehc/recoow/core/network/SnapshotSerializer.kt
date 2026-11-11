package imehc.recoow.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

interface ExtensibleSnapshot {
    val additionalProperties: Map<String, JsonElement>
    /** 新建的快照为 null；解码得到的快照会记住缺失的字段。 */
    val presentFields: Set<String>?
}

/** 保留未知取值，并区分字段缺失与显式 null。 */
@OptIn(ExperimentalSerializationApi::class)
open class SnapshotSerializer<T : ExtensibleSnapshot>(
    private val delegate: KSerializer<T>,
    private val preserve: (T, Map<String, JsonElement>, Set<String>) -> T,
) : KSerializer<T> {
    override val descriptor = delegate.descriptor
    private val knownFields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Snapshots require JSON")
        val fields = input.decodeJsonElement().jsonObject
        val value = input.json.decodeFromJsonElement(delegate, JsonObject(fields.filterKeys { it in knownFields }))
        return preserve(value, fields.filterKeys { it !in knownFields }, fields.keys intersect knownFields)
    }

    override fun serialize(encoder: Encoder, value: T) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Snapshots require JSON")
        require(value.additionalProperties.keys.none { it in knownFields }) { "Unknown fields cannot replace known fields" }
        val completeJson = Json(output.json) { encodeDefaults = true; explicitNulls = true }
        val complete = completeJson.encodeToJsonElement(delegate, value).jsonObject
        val fields = value.presentFields?.let { present ->
            // 即使可选字段在解码快照中缺失，本次新变更的取值仍要写入。
            val nonDefaults = Json(completeJson) { encodeDefaults = false }.encodeToJsonElement(delegate, value).jsonObject
            complete.filterKeys { it in present || it in nonDefaults }
        } ?: complete
        output.encodeJsonElement(JsonObject(value.additionalProperties + fields))
    }
}
