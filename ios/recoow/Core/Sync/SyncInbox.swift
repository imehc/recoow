import Foundation
import GRDB

extension FullSyncRepository {
    nonisolated func receive(_ data: Data, target: SyncTarget) throws -> Bool {
        let pageKey = target.bootstrapComplete ? "pull:\(target.receivedCursor)" : "bootstrap:\(target.bootstrapAfter)"
        try database.writer.write { db in
            try requireCurrent()
            try db.execute(sql: "INSERT OR REPLACE INTO sync_received_pages (scope, page_key, body) VALUES (?, ?, ?)",
                           arguments: [target.scope, pageKey, data])
        }
        guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              envelope["generation"] as? String == target.generation else {
            throw CloudAPIError.invalidResponse("数据集世代与请求不一致，原始响应已保留")
        }
        if !target.bootstrapComplete, envelope["watermark"] as? Int64 != target.bootstrapWatermark {
            throw CloudAPIError.invalidResponse("全量快照水位变化，原始响应已保留")
        }
        let page = try CloudSyncResponseDecoder.pull(data, after: target.bootstrapComplete ? target.receivedCursor : target.bootstrapAfter)
        try database.writer.write { db in
            try requireCurrent()
            guard let current = try SyncTarget.fetchOne(db, sql: "SELECT * FROM sync_targets WHERE scope = ?", arguments: [target.scope]),
                  current.receivedCursor == target.receivedCursor, current.bootstrapAfter == target.bootstrapAfter else {
                throw CloudAPIError.syncRejected("同步进度已改变，已停止应用旧响应")
            }
            for entry in page.entries {
                let update = RemoteRowUpdate(table: entry.table, id: entry.id, version: entry.version, updatedAtMs: entry.updatedAtMs,
                                             deviceID: entry.deviceID, deletedAtMs: entry.deletedAtMs, snapshot: entry.snapshot,
                                             dataVersion: entry.dataVersion)
                let json = try Self.json(update.envelope)
                if let existing = try String.fetchOne(db, sql: "SELECT envelope_json FROM sync_inbox WHERE scope = ? AND version = ?",
                                                     arguments: [target.scope, entry.version]), existing != json {
                    throw CloudAPIError.invalidResponse("同一世代的版本内容发生变化，必须重新初始化服务端世代")
                }
                try db.execute(sql: """
                    INSERT OR IGNORE INTO sync_inbox (scope, version, table_name, entity_id, envelope_json) VALUES (?, ?, ?, ?, ?)
                    """, arguments: [target.scope, entry.version, entry.table, entry.id, json])
            }
            if target.bootstrapComplete {
                try db.execute(sql: "UPDATE sync_targets SET received_cursor = ? WHERE scope = ?", arguments: [page.cursor, target.scope])
            } else {
                guard page.cursor <= target.bootstrapWatermark else { throw CloudAPIError.invalidResponse("快照条目超出水位") }
                try db.execute(sql: "UPDATE sync_targets SET bootstrap_after = ? WHERE scope = ?", arguments: [page.cursor, target.scope])
                if !page.more {
                    try db.execute(sql: "UPDATE sync_targets SET bootstrap_complete = 1, received_cursor = bootstrap_watermark WHERE scope = ?",
                                   arguments: [target.scope])
                }
            }
            try db.execute(sql: "DELETE FROM sync_received_pages WHERE scope = ? AND page_key = ?", arguments: [target.scope, pageKey])
        }
        return page.more
    }

    nonisolated func applyInbox(scope: String) throws -> Int {
        var total = 0
        while true {
            let applied = try database.writer.write { db in
                try requireCurrent()
                let rows = try Row.fetchAll(db, sql: """
                    SELECT version, envelope_json FROM sync_inbox WHERE scope = ? AND applied = 0
                    ORDER BY \(SyncEntityCatalog.sqlOrder), version LIMIT 500
                    """, arguments: [scope])
                var applied = 0
                for row in rows {
                    let update = try CloudSyncResponseDecoder.row(from: Self.object(row["envelope_json"]))
                    if try SyncRowApplier.apply(db: db, scope: scope, update: update) {
                        try db.execute(sql: "UPDATE sync_inbox SET applied = 1, last_error = NULL WHERE scope = ? AND version = ?",
                                       arguments: [scope, update.version])
                        applied += 1
                    } else {
                        try db.execute(sql: "UPDATE sync_inbox SET last_error = '等待关联实体' WHERE scope = ? AND version = ?",
                                       arguments: [scope, update.version])
                    }
                }
                try SyncAggregateProjection.refresh(db: db, scope: scope)
                try db.execute(sql: """
                    UPDATE sync_targets SET applied_cursor = MIN(received_cursor, COALESCE(
                        (SELECT MIN(version) - 1 FROM sync_inbox WHERE scope = ? AND applied = 0), received_cursor)) WHERE scope = ?
                    """, arguments: [scope, scope])
                return applied
            }
            total += applied
            if applied == 0 { break }
        }
        return total
    }

    nonisolated func unappliedCount(scope: String) throws -> Int {
        try database.reader.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM sync_inbox WHERE scope = ? AND applied = 0", arguments: [scope]) ?? 0
        }
    }
}
