import Foundation
import GRDB

nonisolated enum SyncRowApplier {
    static func apply(db: Database, scope: String, update: RemoteRowUpdate) throws -> Bool {
        let entity = try SyncEntityCatalog.entity(update.table)
        var canonical = update
        if let previous = try FullSyncRepository.shadow(db: db, scope: scope, table: update.table, id: update.id),
           previous.version > canonical.version {
            canonical = previous
        }
        let pending = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = ? AND status IN ('pending', 'conflict')",
                                       arguments: [scope, update.table, update.id]) ?? 0
        if pending == 0 {
            if !(try materialize(db: db, scope: scope, entity: entity, update: canonical)) { return false }
        }
        try db.execute(sql: """
            INSERT INTO sync_rows (scope, table_name, entity_id, version, envelope_json) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (scope, table_name, entity_id) DO UPDATE SET version = excluded.version, envelope_json = excluded.envelope_json
            WHERE excluded.version >= sync_rows.version
            """, arguments: [scope, canonical.table, canonical.id, canonical.version, try FullSyncRepository.json(canonical.envelope)])
        return true
    }

    private static func materialize(db: Database, scope: String, entity: SyncEntityDefinition, update: RemoteRowUpdate) throws -> Bool {
        let exists = try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM \"\(entity.table)\" WHERE id = ?)", arguments: [update.id]) ?? false
        if update.isDeleted && (exists || update.snapshot == nil) {
            if entity.table == "bills", let effects = update.snapshot?["stored_value_effects"] as? String {
                _ = try StoredValueBalanceEffects.decode(effects)
                try db.execute(sql: "UPDATE bills SET stored_value_effects = ? WHERE id = ?", arguments: [effects, update.id])
            }
            try db.execute(sql: """
                UPDATE "\(entity.table)" SET deleted_at = ?, updated_at = ?, device_id = ?, server_version = ?, sync_status = 1 WHERE id = ?
                """, arguments: [update.deletedAtMs, update.updatedAtMs, update.deviceID, update.version, update.id])
            try queueNotification(db: db, table: entity.table, id: update.id, version: update.version)
            return true
        }
        guard var object = update.snapshot else { throw CloudAPIError.invalidResponse("\(entity.table) 缺少快照") }
        object["id"] = update.id
        object["updated_at"] = update.updatedAtMs
        object["device_id"] = update.deviceID
        object["deleted_at"] = update.deletedAtMs.map { $0 as Any } ?? NSNull()
        object = try entity.upgrade(object, from: update.dataVersion)
        if entity.table == "media_assets", !update.isDeleted {
            guard let reference = (object["_media"] as? [String: Any])?["object"] as? [String: Any] else {
                throw CloudAPIError.invalidResponse("素材缺少内容寻址引用")
            }
            object["storage_key"] = try SyncMediaReference(object: reference).key
        }
        for dependency in entity.dependencies {
            guard let identifier = object[dependency.field] as? String, !identifier.isEmpty else { continue }
            let available = try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM \"\(dependency.table)\" WHERE id = ?)", arguments: [identifier]) ?? false
            if !available { return false }
        }
        var names: [String] = []
        var values: [DatabaseValue] = []
        for field in entity.fields where !field.derived && field.name != "server_version" {
            if field.binary {
                if !exists && !field.nullable {
                    names.append(field.name)
                    values.append(Data().databaseValue)
                }
                continue
            }
            guard let value = object[field.name] else { continue }
            names.append(field.name)
            if value is NSNull {
                values.append(.null)
            } else if field.type == .text {
                values.append((value as! String).databaseValue)
            } else if field.type == .real {
                values.append((value as! NSNumber).doubleValue.databaseValue)
            } else {
                values.append((value as! NSNumber).int64Value.databaseValue)
            }
        }
        names += ["server_version", "sync_status"]
        values += [update.version.databaseValue, SyncStatus.synced.rawValue.databaseValue]
        let columns = names.map { "\"\($0)\"" }.joined(separator: ",")
        let placeholders = names.map { _ in "?" }.joined(separator: ",")
        let setters = names.filter { $0 != "id" }.map { "\"\($0)\" = excluded.\"\($0)\"" }.joined(separator: ",")
        try db.execute(sql: "INSERT INTO \"\(entity.table)\" (\(columns)) VALUES (\(placeholders)) ON CONFLICT(id) DO UPDATE SET \(setters)",
                       arguments: StatementArguments(values))
        if !update.isDeleted {
            try SyncMediaTransfer.queueDownloads(db: db, scope: scope, update: update)
        }
        try queueNotification(db: db, table: entity.table, id: update.id, version: update.version)
        return true
    }

    static func queueNotification(db: Database, table: String, id: String, version: Int64) throws {
        guard table == "reminders" || table == "anniversaries" else { return }
        try db.execute(sql: """
            INSERT INTO sync_notification_jobs (table_name, entity_id, version) VALUES (?, ?, ?)
            ON CONFLICT (table_name, entity_id) DO UPDATE SET version = excluded.version
            """, arguments: [table, id, version])
    }
}
