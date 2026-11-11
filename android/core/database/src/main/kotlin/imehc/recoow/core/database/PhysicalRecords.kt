package imehc.recoow.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import imehc.recoow.core.database.contract.EntityContract
import kotlinx.serialization.json.*

/** 所有调用都在 Room 事务内执行，原生 SQL 与 DAO 的失效通知共用同一连接。 */
object PhysicalRecords {
    fun copyBinary(db: SupportSQLiteDatabase, table: String, id: String, field: String, output: java.io.File): Long? {
        val contract = imehc.recoow.core.database.generated.EntityContracts.registry.entity(table)
        require(contract.fields.any { it.name == field && it.binary })
        val length = db.query("SELECT length(\"$field\") FROM \"$table\" WHERE id = ?", arrayOf(id)).use {
            if (!it.moveToFirst() || it.isNull(0)) return null
            it.getLong(0)
        }
        require(length in 0..200L * 1024 * 1024)
        output.outputStream().use { stream ->
            var offset = 0L
            while (offset < length) {
                db.query("SELECT substr(\"$field\", ?, ?) FROM \"$table\" WHERE id = ?",
                    arrayOf<Any>(offset + 1, minOf(512 * 1024L, length - offset), id)).use {
                    check(it.moveToFirst()); val part = it.getBlob(0); check(part.isNotEmpty())
                    stream.write(part); offset += part.size
                }
            }
        }
        return length
    }
    fun binary(db: SupportSQLiteDatabase, table: String, id: String, field: String): ByteArray? {
        val length = db.query("SELECT length(\"$field\") FROM \"$table\" WHERE id = ?", arrayOf(id)).use {
            if (!it.moveToFirst() || it.isNull(0)) return null
            it.getInt(0)
        }
        require(length <= 200 * 1024 * 1024)
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            db.query("SELECT substr(\"$field\", ?, ?) FROM \"$table\" WHERE id = ?", arrayOf<Any>(offset + 1, minOf(512 * 1024, length - offset), id)).use {
                check(it.moveToFirst()); val part = it.getBlob(0); check(part.isNotEmpty())
                part.copyInto(result, offset); offset += part.size
            }
        }
        return result
    }

    fun exists(db: SupportSQLiteDatabase, table: String, id: String): Boolean =
        db.query("SELECT 1 FROM \"$table\" WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    fun write(db: SupportSQLiteDatabase, contract: EntityContract, snapshot: JsonObject, version: Long?, local: Boolean,
        binaryFields: Map<String, ByteArray?> = emptyMap()) {
        require(binaryFields.keys.all { name -> contract.fields.any { it.name == name && it.binary } })
        val id = snapshot.getValue("id").jsonPrimitive.content
        val exists = exists(db, contract.table, id)
        val values = linkedMapOf<String, Any?>()
        for (field in contract.fields) {
            if (field.derived || field.name == "server_version") continue
            if (field.binary) {
                if (field.name in binaryFields) {
                    require(field.nullable || binaryFields[field.name] != null)
                    values[field.name] = binaryFields[field.name]
                } else if (!exists && !field.nullable) values[field.name] = ByteArray(0)
                continue
            }
            val value = snapshot[field.name] ?: continue
            values[field.name] = if (value == JsonNull) null else when (field.type) {
                "int" -> value.jsonPrimitive.long
                "real" -> value.jsonPrimitive.double
                "bool" -> if (value.jsonPrimitive.boolean) 1L else 0L
                else -> value.jsonPrimitive.content
            }
        }
        values["server_version"] = version
        values["sync_status"] = if (local) 0L else 1L
        if (exists) {
            values.remove("id")
            db.execSQL("UPDATE \"${contract.table}\" SET ${values.keys.joinToString { "\"$it\" = ?" }} WHERE id = ?",
                (values.values + id).toTypedArray())
        } else {
            db.execSQL("INSERT INTO \"${contract.table}\" (${values.keys.joinToString { "\"$it\"" }}) VALUES (${values.keys.joinToString { "?" }})",
                values.values.toTypedArray())
        }
    }
}
