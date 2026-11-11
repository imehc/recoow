package imehc.recoow.core.database

import androidx.room.migration.Migration

/** 版本 1 是 Android 的首个数据库，此后每提升一次 schema 版本都要补写显式迁移。 */
object DatabaseMigrations {
    val all: List<Migration> = listOf(object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_outbox ADD COLUMN originJson TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS record_origins (table_name TEXT NOT NULL, entity_id TEXT NOT NULL, device_id TEXT NOT NULL, updated_at INTEGER NOT NULL, origin_json TEXT, PRIMARY KEY(table_name, entity_id))")
        }
    })
}
