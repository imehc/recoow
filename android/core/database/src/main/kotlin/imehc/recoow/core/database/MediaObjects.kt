package imehc.recoow.core.database

import imehc.recoow.core.database.contract.EntityContract
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class MediaReference(val key: String, val checksum: String, val size: Long, val contentType: String) {
    init {
        require(checksum.matches(Regex("[a-f0-9]{64}")) && key == "sync/$checksum")
        require(size in 0..200L * 1024 * 1024)
        require(contentType.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")))
    }
    fun json() = buildJsonObject {
        put("key", key); put("checksum", checksum); put("size", size); put("contentType", contentType)
    }
    companion object {
        fun parse(value: JsonElement): MediaReference {
            val raw = value.jsonObject
            fun text(key: String) = raw.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
            val size = raw.getValue("size").jsonPrimitive
            require(!size.isString && size.content.matches(Regex("0|[1-9][0-9]*")))
            return MediaReference(text("key"), text("checksum"), size.long, text("contentType"))
        }
    }
}

/** 文件按内容寻址，发布后不再修改；调用时须处于 DatabaseHost 的门禁内。 */
class MediaObjects(private val directory: File) {
    fun file(reference: MediaReference): File = File(directory, reference.key)
    fun available(reference: MediaReference): Boolean = file(reference).let {
        it.isFile && it.length() == reference.size && checksum(it) == reference.checksum
    }
    fun verified(reference: MediaReference): File = file(reference).also {
        require(it.isFile && it.length() == reference.size && checksum(it) == reference.checksum) { "Missing or damaged media" }
    }
    fun capture(bytes: ByteArray, contentType: String): MediaReference {
        val checksum = digest(MessageDigest.getInstance("SHA-256").digest(bytes))
        val reference = MediaReference("sync/$checksum", checksum, bytes.size.toLong(), contentType)
        val destination = file(reference)
        if (available(reference)) return reference
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val temporary = File.createTempFile("capture-", ".tmp", destination.parentFile)
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(destination))
        } finally { temporary.delete() }
        return reference
    }
    fun capture(file: File, contentType: String): MediaReference {
        require(file.length() <= 200L * 1024 * 1024)
        val checksum = checksum(file)
        val reference = MediaReference("sync/$checksum", checksum, file.length(), contentType)
        install(file, reference)
        return reference
    }
    fun install(source: File, reference: MediaReference) {
        require(source.length() == reference.size && checksum(source) == reference.checksum)
        val destination = file(reference)
        if (available(reference)) return
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val temporary = File.createTempFile("capture-", ".tmp", destination.parentFile)
        try {
            source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            check(temporary.renameTo(destination))
        } finally { temporary.delete() }
    }
    fun captureSnapshot(contract: EntityContract, snapshot: JsonObject, binary: Map<String, ByteArray?>): JsonObject {
        val references = snapshot["_media"]?.jsonObject.orEmpty().toMutableMap()
        val type = snapshot["mime_type"]?.jsonPrimitive?.content ?: "image/jpeg"
        for ((name, bytes) in binary) {
            require(contract.fields.any { it.name == name && it.binary })
            if (contract.table == "media_assets" && name == "inline_data") {
                if (bytes != null) references["object"] = capture(bytes, type).json()
            } else references[name] = bytes?.let { capture(it, type).json() } ?: JsonNull
        }
        if (contract.table == "media_assets" && references["object"] == null && snapshot["deleted_at"] == JsonNull) {
            val key = snapshot["storage_key"]?.jsonPrimitive?.content
            if (key != null) {
                val source = File(directory, key).canonicalFile
                require(source.path.startsWith(directory.canonicalPath + File.separator))
                if (source.isFile) references["object"] = capture(source, type).json()
            }
        }
        references["object"]?.takeIf { contract.table == "media_assets" && it != JsonNull }?.let {
            val ref = MediaReference.parse(it)
            require(snapshot["checksum"]?.jsonPrimitive?.content == ref.checksum && snapshot["byte_count"]?.jsonPrimitive?.long == ref.size)
        }
        return JsonObject(snapshot.toMutableMap().apply {
            if (references.isNotEmpty()) put("_media", JsonObject(references))
            if (contract.table == "media_assets") references["object"]?.takeIf { it != JsonNull }?.let {
                put("storage_key", JsonPrimitive(MediaReference.parse(it).key))
            }
        })
    }
    companion object {
        fun checksum(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
            }
            return digest(hash.digest())
        }
        private fun digest(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
