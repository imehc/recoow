import Foundation
import GRDB

nonisolated struct SyncConflict: Identifiable, Sendable {
    let table: String
    let entityID: String
    let localSnapshot: String
    let remoteSnapshot: String
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
                    var object = SyncEntityCatalog.snapshot(row: local, entity: entity)
                    for field in entity.fields where field.binary { object.removeValue(forKey: field.name) }
                    localSnapshot = try Self.json(object)
                }
                return SyncConflict(table: table, entityID: identifier, localSnapshot: localSnapshot,
                                    remoteSnapshot: try remote.map { try Self.json($0.envelope) } ?? "{}")
            }
        }
    }

    nonisolated func resolveConflict(scope: String, conflict: SyncConflict, keepLocal: Bool) throws {
        try database.writer.write { db in
            try requireCurrent()
            guard try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = ? AND status = 'conflict')",
                                    arguments: [scope, conflict.table, conflict.entityID]) == true else {
                throw CloudAPIError.syncRejected(AppLocalization.string("冲突状态已改变，请刷新列表"))
            }
            guard let remote = try Self.shadow(db: db, scope: scope, table: conflict.table, id: conflict.entityID) else {
                throw CloudAPIError.syncRejected(AppLocalization.string("缺少服务端冲突快照，请先同步"))
            }
            try db.execute(sql: """
                UPDATE sync_outbox SET status = ?, last_error = '用户已选择冲突解决方式；原始快照保留'
                WHERE scope = ? AND table_name = ? AND entity_id = ? AND status IN ('conflict', 'pending')
                """, arguments: [keepLocal ? "resolved_local" : "resolved_remote", scope, conflict.table, conflict.entityID])
            if keepLocal {
                let entity = try SyncEntityCatalog.entity(conflict.table)
                guard let row = try Row.fetchOne(db, sql: "SELECT * FROM \"\(conflict.table)\" WHERE id = ?", arguments: [conflict.entityID]) else {
                    throw CloudAPIError.syncRejected(AppLocalization.string("本地记录不存在"))
                }
                let object = SyncEntityCatalog.snapshot(row: row, entity: entity)
                let payload = try SyncMediaTransfer.capturePayload(JSONSerialization.data(withJSONObject: object), table: conflict.table)
                let deletedAt: Int64? = row["deleted_at"]
                try Self.enqueue(db: db, scope: scope, table: conflict.table, entityID: conflict.entityID,
                                 operation: deletedAt == nil ? .update : .delete,
                                 payloadJSON: String(decoding: payload, as: UTF8.self), localChangeID: nil)
                try db.execute(sql: "UPDATE \"\(conflict.table)\" SET sync_status = ? WHERE id = ?",
                               arguments: [SyncStatus.pending.rawValue, conflict.entityID])
            } else {
                guard try SyncRowApplier.apply(db: db, scope: scope, update: remote) else {
                    throw CloudAPIError.syncRejected(AppLocalization.string("关联记录尚未恢复，未修改本地数据"))
                }
            }
            try SyncAggregateProjection.refresh(db: db, scope: scope)
        }
    }
}
