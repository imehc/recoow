import Foundation
import GRDB

/// 只把之后产生的本地编辑叠加到已确认的服务器快照上。
/// 已准备的请求保持不可变，原始编辑仍留在 change_log 中。
nonisolated enum SyncSnapshotRebase {
    private static func localStorageFields(_ table: String) -> Set<String> {
        table == "media_assets" ? ["storage_key", "storage_backend"] : []
    }
    static let settlementFields: Set<String> = [
        "original_amount_cents", "discount_amount_cents", "final_amount_cents",
        "transaction_type", "category", "payment_method", "stored_value_account_id",
        "settlement_status", "group_buy_valid_until", "redeemed_at", "refund_reason"
    ]

    static func equal(_ first: Any?, _ second: Any?) throws -> Bool {
        guard let first, let second else { return first == nil && second == nil }
        // Foundation 的数值比较可能经 Double 对大整数产生舍入。
        let options: JSONSerialization.WritingOptions = [.sortedKeys, .fragmentsAllowed]
        return try JSONSerialization.data(withJSONObject: first, options: options) == JSONSerialization.data(withJSONObject: second, options: options)
    }

    static func conflicts(table: String, base: [String: Any], local: [String: Any], remote: [String: Any]) throws -> Bool {
        let entity = try SyncEntityCatalog.entity(table)
        let ignored = Set(entity.fields.filter { $0.binary || $0.derived }.map(\.name)).union(["id", "created_at", "updated_at", "device_id", "deleted_at", "server_version", "sync_status", "stored_value_effects"])
        var fields = Set<String>()
        for (key, value) in local where !ignored.union(localStorageFields(table)).contains(key) {
            if try !equal(value, base[key]) { fields.insert(key) }
        }
        if !equalDeletion(base, local) || !equalDeletion(base, remote) { return true }
        if table == "bills", !fields.isDisjoint(with: settlementFields) {
            let remoteChanged = try settlementFields.contains { try !equal(base[$0], remote[$0]) }
            let sameResult = try settlementFields.allSatisfy { try equal(local[$0], remote[$0]) }
            if remoteChanged && !sameResult { return true }
            fields.subtract(settlementFields)
        }
        return try fields.contains { try !equal(remote[$0], base[$0]) && !equal(local[$0], remote[$0]) }
    }

    private static func equalDeletion(_ first: [String: Any], _ second: [String: Any]) -> Bool {
        func deleted(_ fields: [String: Any]) -> Bool { ((fields["deleted_at"] as? NSNumber)?.int64Value ?? 0) > 0 }
        return deleted(first) == deleted(second)
    }

    static func snapshot(table: String, base: [String: Any], local: [String: Any], remote: [String: Any]) throws -> [String: Any] {
        let entity = try SyncEntityCatalog.entity(table)
        var result = remote
        let ignored = Set(entity.fields.filter { $0.derived || $0.binary }.map(\.name)).union(["id", "created_at", "server_version", "sync_status", "stored_value_effects"])
        var changed = Set<String>()
        for (key, value) in local where !ignored.union(localStorageFields(table)).contains(key) {
            if try !equal(value, base[key]) { changed.insert(key) }
        }
        if table == "bills", !changed.isDisjoint(with: settlementFields) { changed.formUnion(settlementFields) }
        for key in changed { result[key] = local[key] }
        // 即使只是重复某个业务值，排队编辑也靠时间戳区分。
        for key in ["updated_at", "device_id", "deleted_at"] { result[key] = local[key] }
        if table == "bills" {
            var effects = try StoredValueBalanceEffects.decode(remote["stored_value_effects"] as? String)
            let timestamp = (local["updated_at"] as? NSNumber)?.int64Value ?? 0
            for (fields, subtract) in [(remote, true), (result, false)] {
                guard let account = fields["stored_value_account_id"] as? String, !account.isEmpty else { continue }
                var delta = try balanceDelta(fields, timestamp: timestamp)
                if subtract {
                    guard delta != .min else { throw CloudAPIError.syncRejected(AppLocalization.string("储值余额超出范围，未保存修改")) }
                    delta = -delta
                }
                try StoredValueBalanceEffects.adding(delta, accountID: account, to: &effects)
            }
            result["stored_value_effects"] = try StoredValueBalanceEffects.encode(effects)
        }
        return result
    }

    private static func balanceDelta(_ fields: [String: Any], timestamp: Int64) throws -> Int64 {
        if fields["deleted_at"] != nil && !(fields["deleted_at"] is NSNull) || fields["settlement_status"] as? String == "refunded" { return 0 }
        let type = fields["transaction_type"] as? String
        if type == "expense", fields["category"] as? String == "groupBuy", fields["settlement_status"] as? String == "active",
           let valid = fields["group_buy_valid_until"] as? NSNumber, valid.int64Value < timestamp { return 0 }
        if type == "expense" { return (fields["original_amount_cents"] as? NSNumber)?.int64Value ?? 0 }
        if type == "storedValueUse" {
            let amount = (fields["final_amount_cents"] as? NSNumber)?.int64Value ?? 0
            guard amount != .min else { throw CloudAPIError.syncRejected(AppLocalization.string("储值余额超出范围，未保存修改")) }
            return -amount
        }
        return 0
    }

    static func pending(db: Database, scope: String, acknowledgedID: Int64, update: RemoteRowUpdate) throws {
        guard let original = try String.fetchOne(db, sql: "SELECT payload_json FROM sync_outbox WHERE id = ?", arguments: [acknowledgedID]), let canonical = update.snapshot else { return }
        let queued = try Row.fetchAll(db, sql: """
            SELECT id, payload_json, prepared_json, predecessor_id, capture_pending FROM sync_outbox
            WHERE scope = ? AND table_name = ? AND entity_id = ? AND status = 'pending' AND id > ? ORDER BY id
            """, arguments: [scope, update.table, update.id, acknowledgedID])
        var base = try FullSyncRepository.object(original)
        var rebased = canonical
        rebased["deleted_at"] = update.deletedAtMs.map { $0 as Any } ?? NSNull()
        var predecessorID = acknowledgedID
        let originalVersion = try Int64.fetchOne(db, sql: "SELECT base_version FROM sync_outbox WHERE id = ?", arguments: [acknowledgedID]) ?? 0
        for row in queued {
            guard row["predecessor_id"] as Int64? == predecessorID else { return }
            // 绝不修改可能已经送达服务器的请求。
            guard row["prepared_json"] as String? == nil else { throw CloudAPIError.syncRejected(AppLocalization.string("待同步请求状态已改变")) }
            if row["capture_pending"] as Bool {
                // 在媒体采集成功之前，保持未采集的字节与引用不变。
                try db.execute(sql: "UPDATE sync_outbox SET predecessor_id = NULL, base_version = ? WHERE id = ?", arguments: [originalVersion, row["id"] as Int64])
                return
            }
            let next = try FullSyncRepository.object(row["payload_json"])
            if try conflicts(table: update.table, base: base, local: next, remote: rebased) {
                // 保留原始祖先，使后端能报告进行中的冲突。
                try db.execute(sql: "UPDATE sync_outbox SET predecessor_id = NULL, base_version = ? WHERE id = ?", arguments: [originalVersion, row["id"] as Int64])
                return
            }
            rebased = try snapshot(table: update.table, base: base, local: next, remote: rebased)
            try db.execute(sql: "UPDATE sync_outbox SET payload_json = ? WHERE id = ?", arguments: [try FullSyncRepository.json(rebased), row["id"] as Int64])
            base = next
            predecessorID = row["id"]
        }
        guard !queued.isEmpty else { return }
        // 之后的编辑同样基于重基后的可见记录进行。
        let entity = try SyncEntityCatalog.entity(update.table)
        let fields = entity.fields.filter { !$0.binary && !$0.derived && !["id", "server_version"].contains($0.name) && rebased[$0.name] != nil }
        let values = try fields.map { field -> DatabaseValue in
            let value = rebased[field.name]!
            if value is NSNull { return .null }
            if field.type == .text { return (value as! String).databaseValue }
            if field.type == .real { return (value as! NSNumber).doubleValue.databaseValue }
            return (value as! NSNumber).int64Value.databaseValue
        }
        let setters = fields.map { "\"\($0.name)\" = ?" }.joined(separator: ",")
        try db.execute(sql: "UPDATE \"\(update.table)\" SET \(setters), sync_status = ? WHERE id = ?",
                       arguments: StatementArguments(values + [SyncStatus.pending.rawValue.databaseValue, update.id.databaseValue]))
    }
}
