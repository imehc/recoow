import Foundation
import GRDB

enum V34FullSyncSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v34_full_sync_schema") { db in
            try db.execute(sql: "ALTER TABLE bills ADD COLUMN stored_value_effects TEXT")
            try db.execute(sql: """
                UPDATE bills SET stored_value_effects = CASE
                    WHEN stored_value_account_id IS NOT NULL AND deleted_at IS NULL AND settlement_status != 'refunded'
                    THEN json_object(stored_value_account_id, CASE transaction_type
                        WHEN 'expense' THEN original_amount_cents WHEN 'storedValueUse' THEN -final_amount_cents ELSE 0 END)
                    ELSE '{}' END
                """)
            try db.execute(sql: "ALTER TABLE change_log ADD COLUMN mutation_id TEXT")
            try db.execute(sql: "UPDATE change_log SET mutation_id = lower(hex(randomblob(16))) WHERE mutation_id IS NULL")
            try db.execute(sql: "CREATE UNIQUE INDEX idx_change_log_mutation_id ON change_log (mutation_id)")
            try db.execute(sql: """
                CREATE TABLE sync_targets (
                    scope TEXT PRIMARY KEY, connection_id TEXT NOT NULL, account_id TEXT NOT NULL,
                    generation TEXT NOT NULL, received_cursor INTEGER NOT NULL DEFAULT 0,
                    applied_cursor INTEGER NOT NULL DEFAULT 0, bootstrap_watermark INTEGER NOT NULL,
                    bootstrap_after INTEGER NOT NULL DEFAULT 0, bootstrap_complete INTEGER NOT NULL DEFAULT 0,
                    seeded INTEGER NOT NULL DEFAULT 0, failure_count INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE sync_outbox (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
                    mutation_id TEXT NOT NULL UNIQUE, table_name TEXT NOT NULL, entity_id TEXT NOT NULL,
                    operation TEXT NOT NULL, payload_json TEXT NOT NULL, base_version INTEGER NOT NULL,
                    predecessor_id INTEGER, prepared_json TEXT, local_change_id INTEGER,
                    status TEXT NOT NULL DEFAULT 'pending', server_version INTEGER,
                    attempt_count INTEGER NOT NULL DEFAULT 0, last_error TEXT,
                    capture_pending INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX idx_sync_outbox_pending ON sync_outbox (scope, status, table_name, entity_id, id);
                CREATE TABLE sync_rows (
                    scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
                    table_name TEXT NOT NULL, entity_id TEXT NOT NULL, version INTEGER NOT NULL,
                    envelope_json TEXT NOT NULL, PRIMARY KEY (scope, table_name, entity_id)
                );
                CREATE TABLE sync_inbox (
                    scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
                    version INTEGER NOT NULL, table_name TEXT NOT NULL, entity_id TEXT NOT NULL,
                    envelope_json TEXT NOT NULL, applied INTEGER NOT NULL DEFAULT 0, last_error TEXT,
                    PRIMARY KEY (scope, version)
                );
                CREATE INDEX idx_sync_inbox_pending ON sync_inbox (scope, applied, table_name, version);
                CREATE TABLE sync_received_pages (
                    scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
                    page_key TEXT NOT NULL, body BLOB NOT NULL, PRIMARY KEY (scope, page_key)
                );
                CREATE TABLE sync_media_jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
                    direction TEXT NOT NULL, object_key TEXT NOT NULL, checksum TEXT NOT NULL,
                    size INTEGER NOT NULL, content_type TEXT NOT NULL, table_name TEXT NOT NULL,
                    entity_id TEXT NOT NULL, column_name TEXT NOT NULL, storage_key TEXT,
                    version INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
                    attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT, UNIQUE (scope, direction, table_name, entity_id, column_name, checksum)
                );
                CREATE TABLE sync_notification_jobs (
                    table_name TEXT NOT NULL, entity_id TEXT NOT NULL, version INTEGER NOT NULL,
                    PRIMARY KEY (table_name, entity_id)
                );
                """)
            try db.create(table: "stored_value_adjustments") { definition in
                definition.syncMetadata()
                definition.column("account_id", .text).notNull().references("stored_value_accounts")
                definition.column("amount_cents", .integer).notNull()
                definition.column("reason", .text).notNull()
            }
            try db.create(index: "idx_stored_value_adjustments_account", on: "stored_value_adjustments", columns: ["account_id"])
            try db.create(table: "reminder_completions") { definition in
                definition.syncMetadata()
                definition.column("reminder_id", .text).notNull().references("reminders")
                definition.column("date_key", .text).notNull()
                definition.column("completed_at", .integer).notNull()
                definition.column("kind", .text).notNull()
                definition.column("note", .text)
                definition.uniqueKey(["reminder_id", "date_key"])
            }
            try db.execute(sql: """
                INSERT INTO stored_value_adjustments
                    (id, created_at, updated_at, device_id, account_id, amount_cents, reason)
                SELECT 'opening:' || accounts.id, accounts.created_at, accounts.updated_at, accounts.device_id, accounts.id,
                    accounts.balance_cents - COALESCE((
                        SELECT SUM(CASE transaction_type
                            WHEN 'expense' THEN original_amount_cents WHEN 'storedValueUse' THEN -final_amount_cents ELSE 0 END)
                        FROM bills WHERE stored_value_account_id = accounts.id
                            AND deleted_at IS NULL AND settlement_status != 'refunded'
                    ), 0), 'migration'
                FROM stored_value_accounts AS accounts;
                """)
            if try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM stored_value_adjustments WHERE typeof(amount_cents) != 'integer'") != 0 {
                throw DatabaseError(message: "期初储值余额超出整数范围，已取消迁移并保留原始数据库")
            }
            let reminders = try Row.fetchAll(db, sql: "SELECT id, created_at, updated_at, device_id, completion_records, completed_date_keys FROM reminders")
            for reminder in reminders {
                let identifier: String = reminder["id"]
                let updatedAt: Int64 = reminder["updated_at"]
                let createdAt: Int64 = reminder["created_at"]
                let deviceID: String = reminder["device_id"]
                let recordsJSON: String? = reminder["completion_records"]
                var details: [[String: Any]] = []
                if let recordsJSON, !recordsJSON.isEmpty {
                    guard let parsed = try JSONSerialization.jsonObject(with: Data(recordsJSON.utf8)) as? [[String: Any]] else {
                        throw DatabaseError(message: "无法迁移打卡明细 \(identifier)")
                    }
                    details = parsed
                }
                let existingKeys = Set(details.compactMap { $0["dateKey"] as? String })
                let legacyKeys: String? = reminder["completed_date_keys"]
                for dateKey in (legacyKeys ?? "").split(separator: ",").map(String.init) where !existingKeys.contains(dateKey) {
                    details.append(["dateKey": dateKey, "completedAt": updatedAt, "kindRawValue": "checkIn"])
                }
                for detail in details {
                    guard let dateKey = detail["dateKey"] as? String,
                          let completedAt = detail["completedAt"] as? Int64,
                          let kind = detail["kindRawValue"] as? String else {
                        throw DatabaseError(message: "打卡明细字段不完整 \(identifier)")
                    }
                    try db.execute(sql: """
                        INSERT OR REPLACE INTO reminder_completions
                            (id, created_at, updated_at, device_id, reminder_id, date_key, completed_at, kind, note)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, arguments: [identifier + ":" + dateKey, createdAt, updatedAt, deviceID, identifier,
                                         dateKey, completedAt, kind, detail["note"] as? String])
                }
            }
        }
    }
}
