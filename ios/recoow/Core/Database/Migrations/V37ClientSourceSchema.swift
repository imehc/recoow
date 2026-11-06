import GRDB

enum V37ClientSourceSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v37_client_source") { db in
            try db.execute(sql: """
                ALTER TABLE sync_outbox ADD COLUMN origin_json TEXT;
                ALTER TABLE sync_targets ADD COLUMN supports_origin INTEGER NOT NULL DEFAULT 0;
                CREATE TABLE record_origins (
                    table_name TEXT NOT NULL, entity_id TEXT NOT NULL,
                    device_id TEXT NOT NULL, updated_at INTEGER NOT NULL, origin_json TEXT,
                    PRIMARY KEY (table_name, entity_id)
                );
                """)
        }
    }
}
