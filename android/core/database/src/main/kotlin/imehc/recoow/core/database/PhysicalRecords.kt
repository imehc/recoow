package imehc.recoow.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import imehc.recoow.core.database.contract.EntityContract
import kotlinx.serialization.json.*

/** All calls run inside Room's transaction, so raw SQL and DAO invalidations share one connection. */
object PhysicalRecords {
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
