import Foundation
import GRDB

nonisolated struct SyncConflict: Identifiable, Sendable {
    let table: String
    let entityID: String
    let localSnapshot: String
    let remoteSnapshot: String
    let remoteVersion: Int64
    var id: String { table + "/" + entityID }
}

extension FullSyncRepository {
    nonisolated func conflicts(scope: String) throws -> [SyncConflict] {
        try database.reader.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT table_name, entity_id, payload_json FROM sync_outbox
                WHERE scope = ? AND status = 'conflict' GROUP BY table_name, entity_id ORDER BY id
                """, arguments: [scope])
            return try rows.map { row in
                let table: String = row["table_name"]
                let identifier: String = row["entity_id"]
                let remote = try Self.shadow(db: db, scope: scope, table: table, id: identifier)
                var localSnapshot: String = row["payload_json"]
                let entity = try SyncEntityCatalog.entity(table)
                if let local = try Row.fetchOne(db, sql: "SELECT * FROM \"\(table)\" WHERE id = ?", arguments: [identifier]) {
                    localSnapshot = try Self.conflictSnapshot(row: local, entity: entity)
                }
                return SyncConflict(table: table, entityID: identifier, localSnapshot: localSnapshot,
                                    remoteSnapshot: try remote.map { try Self.json($0.envelope) } ?? "{}", remoteVersion: remote?.version ?? 0)
            }
        }
    }

    nonisolated func resolveConflict(scope: String, conflict: SyncConflict, keepLocal: Bool) throws {
        try resolveConflicts(scope: scope, conflicts: [conflict], keepLocal: keepLocal)
    }

    /// 整批在同一个事务校验和入队；任意记录已变化时整批回滚。
    nonisolated func resolveConflicts(scope: String, conflicts: [SyncConflict], keepLocal: Bool) throws {
        try database.writer.write { db in
            try requireCurrent()
            for conflict in conflicts {
                guard try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = ? AND status = 'conflict')",
                                        arguments: [scope, conflict.table, conflict.entityID]) == true else {
                    throw CloudAPIError.syncRejected(AppLocalization.string("冲突状态已改变，请刷新列表"))
                }
                guard let remote = try Self.shadow(db: db, scope: scope, table: conflict.table, id: conflict.entityID) else {
                    throw CloudAPIError.syncRejected(AppLocalization.string("缺少服务端冲突快照，请先同步"))
                }
                guard remote.version == conflict.remoteVersion else {
                    throw CloudAPIError.syncRejected(AppLocalization.string("冲突状态已改变，请刷新列表"))
                }
                let entity = try SyncEntityCatalog.entity(conflict.table)
                guard let row = try Row.fetchOne(db, sql: "SELECT * FROM \"\(conflict.table)\" WHERE id = ?", arguments: [conflict.entityID]) else {
                    throw CloudAPIError.syncRejected(AppLocalization.string("本地记录不存在"))
                }
                guard try Self.conflictSnapshot(row: row, entity: entity) == conflict.localSnapshot else {
                    throw CloudAPIError.syncRejected(AppLocalization.string("本地记录已改变，请刷新列表"))
                }
                let base = try Int64.fetchOne(db, sql: "SELECT base_version FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = ? AND status = 'conflict' ORDER BY id DESC LIMIT 1",
                                             arguments: [scope, conflict.table, conflict.entityID]) ?? 0
                try db.execute(sql: """
                    UPDATE sync_outbox SET status = ?, last_error = '用户已选择冲突解决方式；原始快照保留'
                    WHERE scope = ? AND table_name = ? AND entity_id = ? AND status IN ('conflict', 'pending')
                    """, arguments: [keepLocal ? "resolved_local" : "resolved_remote", scope, conflict.table, conflict.entityID])
                let object = SyncEntityCatalog.snapshot(row: row, entity: entity)
                let payload = try SyncMediaTransfer.capturePayload(JSONSerialization.data(withJSONObject: object), table: conflict.table)
                var snapshot = try Self.object(String(decoding: payload, as: UTF8.self))
                if let previous = remote.snapshot {
                    let known = Set(entity.fields.map(\.name))
                    for (key, value) in previous where !known.contains(key) && snapshot[key] == nil { snapshot[key] = value }
                }
                for field in entity.fields where field.binary || field.derived { snapshot.removeValue(forKey: field.name) }
                snapshot.removeValue(forKey: "server_version")
                snapshot.removeValue(forKey: "sync_status")
                let deletedAt: Int64? = row["deleted_at"]
                let operation: ChangeOperation = deletedAt == nil ? .update : .delete
                try Self.enqueue(db: db, scope: scope, table: conflict.table, entityID: conflict.entityID,
                                 operation: operation, payloadJSON: String(decoding: payload, as: UTF8.self), localChangeID: nil)
                let item = CloudPushItem(table: conflict.table, id: conflict.entityID, operation: operation.rawValue,
                                         updatedAtMs: (snapshot["updated_at"] as! NSNumber).int64Value,
                                         deviceID: snapshot["device_id"] as! String, snapshot: snapshot,
                                         mutationID: try String.fetchOne(db, sql: "SELECT mutation_id FROM sync_outbox WHERE id = ?", arguments: [db.lastInsertedRowID])!,
                                         baseVersion: base, dataVersion: entity.version,
                                         resolution: keepLocal ? "local" : "remote", expectedVersion: remote.version)
                try db.execute(sql: "UPDATE sync_outbox SET prepared_json = ?, base_version = ? WHERE id = ?",
                               arguments: [try Self.json(RecoowAPIClient.object(for: item)), base, db.lastInsertedRowID])
                try SyncMediaTransfer.queueUploads(db: db, scope: scope, item: item)
                try db.execute(sql: "UPDATE \"\(conflict.table)\" SET sync_status = ? WHERE id = ?",
                               arguments: [SyncStatus.pending.rawValue, conflict.entityID])
            }
            try SyncAggregateProjection.refresh(db: db, scope: scope)
        }
    }

    nonisolated private static func conflictSnapshot(row: Row, entity: SyncEntityDefinition) throws -> String {
        var object = SyncEntityCatalog.snapshot(row: row, entity: entity)
        var media: [String: Any] = [:]
        let contentType = object["mime_type"] as? String ?? "image/jpeg"
        for field in entity.fields where field.binary {
            if let encoded = object[field.name] as? String, let bytes = Data(base64Encoded: encoded), !bytes.isEmpty {
                media[field.name] = SyncMediaReference(data: bytes, contentType: contentType).object
            } else { media[field.name] = NSNull() }
            object.removeValue(forKey: field.name)
        }
        if entity.table == "media_assets", let checksum = object["checksum"] as? String {
            media.removeValue(forKey: "inline_data")
            media["object"] = ["key": "sync/" + checksum, "checksum": checksum,
                               "size": object["byte_count"] ?? 0, "contentType": contentType]
        }
        if !media.isEmpty { object["_media"] = media }
        return try Self.json(object)
    }
}
