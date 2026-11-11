package imehc.recoow.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.database.generated.RecoowDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaTest {
    @Test fun versionOneSchemaReopensWithoutLosingRowsOrSynchronizationState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "schema-history-${java.util.UUID.randomUUID()}"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val room = Room.databaseBuilder(context, RecoowDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.all.toTypedArray()).build()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                instrumentation.context.assets.open("recoow-database-v1.sql").bufferedReader().useLines { lines ->
                    lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("--") }.forEach { db.execSQL(it) }
                }
                db.execSQL("INSERT INTO decision_collections(id,created_at,updated_at,device_id,title,sync_status) VALUES('id',1,2,'device','title',0)")
                db.execSQL("INSERT INTO sync_targets(scope,connectionId,accountId,generation,receivedCursor,appliedCursor,bootstrapWatermark,bootstrapAfter,bootstrapComplete,seeded,failureCount,nextAttemptAt) VALUES('scope','c','a','g',42,40,42,0,1,1,0,0)")
            }
            // 不预置 room_master_table，必须让 Room 自行校验历史 SQL 后才接受该数据库。
            val db = room.openHelper.writableDatabase
            assertEquals(DatabaseHost.VERSION, db.version)
            db.query("SELECT title FROM decision_collections WHERE id='id'").use { it.moveToFirst(); assertEquals("title", it.getString(0)) }
            db.query("SELECT receivedCursor,appliedCursor FROM sync_targets WHERE scope='scope'").use {
                it.moveToFirst(); assertEquals(42L, it.getLong(0)); assertEquals(40L, it.getLong(1))
            }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
