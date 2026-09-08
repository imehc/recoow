import Foundation
import GRDB

nonisolated struct SyncTarget: FetchableRecord, Decodable, Sendable {
    let scope: String
    let connectionID: String
    let accountID: String
    let generation: String
    let receivedCursor: Int64
    let appliedCursor: Int64
    let bootstrapWatermark: Int64
    let bootstrapAfter: Int64
    let bootstrapComplete: Bool
    let seeded: Bool
    let failureCount: Int
    let nextAttemptAt: Int64

    enum CodingKeys: String, CodingKey {
        case scope, generation, seeded
        case connectionID = "connection_id"
        case accountID = "account_id"
        case receivedCursor = "received_cursor"
        case appliedCursor = "applied_cursor"
        case bootstrapWatermark = "bootstrap_watermark"
        case bootstrapAfter = "bootstrap_after"
        case bootstrapComplete = "bootstrap_complete"
        case failureCount = "failure_count"
        case nextAttemptAt = "next_attempt_at"
    }
}

nonisolated struct PreparedSyncMutation: @unchecked Sendable {
    let outboxID: Int64
    let item: CloudPushItem
}

nonisolated final class FullSyncRepository: @unchecked Sendable {
    let database: AppDatabase
    let isCurrent: @Sendable () -> Bool

    init(database: AppDatabase, isCurrent: @escaping @Sendable () -> Bool = { true }) {
        self.database = database
        self.isCurrent = isCurrent
    }

    func requireCurrent() throws {
        guard database.syncAccessToken != nil, isCurrent() else {
            throw CloudAPIError.syncRejected("连接或本地数据库已改变，未应用旧同步任务")
        }
    }

    func prepareTarget(connectionID: String, capabilities: CloudCapabilities) throws -> SyncTarget {
        let scope = connectionID + ":" + capabilities.dataset.generation
        let isNew = try database.reader.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM sync_targets WHERE scope = ?", arguments: [scope]) == 0
        }
        if isNew { try createSafetyBackup() }
        try database.writer.write { db in
            try requireCurrent()
            try db.execute(sql: """
                INSERT OR IGNORE INTO sync_targets (scope, connection_id, account_id, generation, bootstrap_watermark)
                VALUES (?, ?, ?, ?, ?)
                """, arguments: [scope, connectionID, capabilities.accountId, capabilities.dataset.generation, capabilities.dataset.head])
            guard var target = try SyncTarget.fetchOne(db, sql: "SELECT * FROM sync_targets WHERE scope = ?", arguments: [scope]) else {
                throw CloudAPIError.syncRejected("同步状态无法创建")
            }
            if target.receivedCursor > capabilities.dataset.head || target.receivedCursor < capabilities.dataset.minCursor {
                try Self.reset(db: db, scope: scope, watermark: capabilities.dataset.head)
                target = try SyncTarget.fetchOne(db, sql: "SELECT * FROM sync_targets WHERE scope = ?", arguments: [scope])!
            }
            if !target.seeded {
                try db.execute(sql: "UPDATE sync_targets SET bootstrap_watermark = ? WHERE scope = ?",
                               arguments: [capabilities.dataset.head, scope])
                try Self.seed(db: db, scope: scope)
                try db.execute(sql: "UPDATE sync_targets SET seeded = 1 WHERE scope = ?", arguments: [scope])
            }
        }
        return try target(scope: scope)
    }

    func target(scope: String) throws -> SyncTarget {
        try database.reader.read { db in
            guard let target = try SyncTarget.fetchOne(db, sql: "SELECT * FROM sync_targets WHERE scope = ?", arguments: [scope]) else {
                throw CloudAPIError.syncRejected("同步状态已改变，请重新同步")
            }
            return target
        }
    }

    static func reset(db: Database, scope: String, watermark: Int64) throws {
        try db.execute(sql: "UPDATE sync_outbox SET status = 'reset', last_error = '游标失效，已重新盘点；保留原始修改' WHERE scope = ? AND status IN ('pending', 'conflict')", arguments: [scope])
        for table in ["sync_rows", "sync_inbox", "sync_received_pages", "sync_media_jobs"] {
            try db.execute(sql: "DELETE FROM \(table) WHERE scope = ?", arguments: [scope])
        }
        try db.execute(sql: """
            UPDATE sync_targets SET received_cursor = 0, applied_cursor = 0, bootstrap_after = 0,
                bootstrap_complete = 0, bootstrap_watermark = ?, seeded = 0 WHERE scope = ?
            """, arguments: [watermark, scope])
    }

    static func seed(db: Database, scope: String) throws {
        for entity in SyncEntityCatalog.entities {
            let rows = try Row.fetchAll(db, sql: """
                SELECT * FROM "\(entity.table)" AS source
                WHERE NOT EXISTS (SELECT 1 FROM sync_rows WHERE scope = ? AND table_name = ? AND entity_id = source.id)
                AND NOT EXISTS (SELECT 1 FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = source.id AND status = 'pending')
                """, arguments: [scope, entity.table, scope, entity.table])
            for row in rows {
                let object = SyncEntityCatalog.snapshot(row: row, entity: entity)
                let raw = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
                let payload = try SyncMediaTransfer.capturePayload(raw, table: entity.table)
                let deletedAt: Int64? = row["deleted_at"]
                try enqueue(db: db, scope: scope, table: entity.table, entityID: row["id"],
                            operation: deletedAt == nil ? .insert : .delete,
                            payloadJSON: String(decoding: payload, as: UTF8.self), localChangeID: nil)
            }
        }
    }

    static func enqueue(db: Database, scope: String, table: String, entityID: String,
                        operation: ChangeOperation, payloadJSON: String, localChangeID: Int64?, capturePending: Bool = false) throws {
        let version = try Int64.fetchOne(db, sql: "SELECT version FROM sync_rows WHERE scope = ? AND table_name = ? AND entity_id = ?",
                                        arguments: [scope, table, entityID]) ?? 0
        let predecessor = try Int64.fetchOne(db, sql: """
            SELECT id FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = ? AND status = 'pending' ORDER BY id DESC LIMIT 1
            """, arguments: [scope, table, entityID])
        try db.execute(sql: """
            INSERT INTO sync_outbox (scope, mutation_id, table_name, entity_id, operation, payload_json, base_version, predecessor_id, local_change_id, capture_pending)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, arguments: [scope, UUID().uuidString, table, entityID, operation.rawValue, payloadJSON, version, predecessor, localChangeID, capturePending])
    }

    func pending(scope: String, limit: Int = 40) throws -> [PreparedSyncMutation] {
        try database.writer.write { db in
            try requireCurrent()
            let rows = try Row.fetchAll(db, sql: """
                SELECT * FROM sync_outbox AS candidate WHERE scope = ? AND status = 'pending'
                AND NOT EXISTS (SELECT 1 FROM sync_outbox AS earlier WHERE earlier.scope = candidate.scope
                    AND earlier.table_name = candidate.table_name AND earlier.entity_id = candidate.entity_id
                    AND earlier.status = 'pending' AND earlier.id < candidate.id)
                AND NOT EXISTS (SELECT 1 FROM sync_outbox AS conflict WHERE conflict.scope = candidate.scope
                    AND conflict.table_name = candidate.table_name AND conflict.entity_id = candidate.entity_id AND conflict.status = 'conflict')
                ORDER BY \(SyncEntityCatalog.sqlOrder), id LIMIT ?
                """, arguments: [scope, limit])
            return try rows.map { row in
                let outboxID: Int64 = row["id"]
                if let prepared: String = row["prepared_json"] {
                    return PreparedSyncMutation(outboxID: outboxID, item: try Self.decodePush(prepared))
                }
                let table: String = row["table_name"]
                let entityID: String = row["entity_id"]
                let entity = try SyncEntityCatalog.entity(table)
                var json: String = row["payload_json"]
                if row["capture_pending"] as Bool {
                    let captured = try SyncMediaTransfer.capturePayload(Data(json.utf8), table: table)
                    json = String(decoding: captured, as: UTF8.self)
                    try db.execute(sql: "UPDATE sync_outbox SET payload_json = ?, capture_pending = 0 WHERE id = ?", arguments: [json, outboxID])
                }
                var object = try Self.object(json)
                if let shadow = try Self.shadow(db: db, scope: scope, table: table, id: entityID), let snapshot = shadow.snapshot {
                    let fields = Set(entity.fields.map(\.name))
                    for (key, value) in snapshot where !fields.contains(key) && object[key] == nil {
                        object[key] = value
                    }
                }
                for field in entity.fields where field.derived || field.binary { object.removeValue(forKey: field.name) }
                object.removeValue(forKey: "server_version")
                object.removeValue(forKey: "sync_status")
                var baseVersion: Int64 = row["base_version"]
                if let predecessor: Int64 = row["predecessor_id"],
                   let previous = try Row.fetchOne(db, sql: "SELECT status, server_version FROM sync_outbox WHERE id = ?", arguments: [predecessor]),
                   ["applied", "duplicate"].contains(previous["status"] as String) {
                    baseVersion = previous["server_version"]
                }
                guard let updatedAt = object["updated_at"] as? Int64, let deviceID = object["device_id"] as? String else {
                    throw CloudAPIError.syncRejected("本地 \(table)/\(entityID) 缺少同步元数据")
                }
                let item = CloudPushItem(table: table, id: entityID, operation: row["operation"], updatedAtMs: updatedAt,
                                         deviceID: deviceID, snapshot: object, mutationID: row["mutation_id"],
                                         baseVersion: baseVersion, dataVersion: entity.version)
                let encoded = try Self.json(RecoowAPIClient.object(for: item))
                try db.execute(sql: "UPDATE sync_outbox SET prepared_json = ?, base_version = ? WHERE id = ?", arguments: [encoded, baseVersion, outboxID])
                try SyncMediaTransfer.queueUploads(db: db, scope: scope, item: item)
                return PreparedSyncMutation(outboxID: outboxID, item: item)
            }
        }
    }

    func acknowledge(scope: String, pending: [PreparedSyncMutation], response: CloudPushResponse) throws -> Int {
        try database.writer.write { db in
            try requireCurrent()
            var conflicts = 0
            for (mutation, result) in zip(pending, response.results) {
                guard [.applied, .duplicate, .conflict].contains(result.status) else {
                    try db.execute(sql: "UPDATE sync_outbox SET attempt_count = attempt_count + 1, last_error = ? WHERE id = ?",
                                   arguments: [result.status.rawValue, mutation.outboxID])
                    continue
                }
                if result.status == .conflict { conflicts += 1 }
                try db.execute(sql: "UPDATE sync_outbox SET status = ?, server_version = ?, last_error = ? WHERE id = ?",
                               arguments: [result.status.rawValue, result.version,
                                           result.status == .conflict ? "修订冲突；已保留本地原始快照" : nil, mutation.outboxID])
                try db.execute(sql: """
                    UPDATE change_log SET synced_at_ms = ?, last_error = ?
                    WHERE id = (SELECT local_change_id FROM sync_outbox WHERE id = ?)
                    """, arguments: [SyncableTimestamp.nowMilliseconds(), result.status == .conflict ? "修订冲突" : nil, mutation.outboxID])
                if let canonical = result.current {
                    if !(try SyncRowApplier.apply(db: db, scope: scope, update: canonical)) {
                        try db.execute(sql: "INSERT OR IGNORE INTO sync_inbox (scope, version, table_name, entity_id, envelope_json) VALUES (?, ?, ?, ?, ?)",
                                       arguments: [scope, canonical.version, canonical.table, canonical.id, try Self.json(canonical.envelope)])
                    }
                    if result.status == .conflict {
                        try db.execute(sql: "UPDATE \"\(canonical.table)\" SET sync_status = ? WHERE id = ?",
                                       arguments: [SyncStatus.conflicted.rawValue, canonical.id])
                    }
                } else {
                    throw CloudAPIError.invalidResponse("v2 回执缺少权威快照")
                }
            }
            try SyncAggregateProjection.refresh(db: db, scope: scope)
            return conflicts
        }
    }

    func pendingCount(scope: String) throws -> Int {
        try database.reader.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM sync_outbox WHERE scope = ? AND status = 'pending'", arguments: [scope]) ?? 0
        }
    }

    func markFailure(scope: String, message: String) throws -> TimeInterval {
        try database.writer.write { db in
            try requireCurrent()
            let failures = (try Int.fetchOne(db, sql: "SELECT failure_count FROM sync_targets WHERE scope = ?", arguments: [scope]) ?? 0) + 1
            let delay = min(300.0, pow(2, Double(min(failures, 8)))) + Double.random(in: 0...1)
            try db.execute(sql: "UPDATE sync_targets SET failure_count = ?, next_attempt_at = ? WHERE scope = ?",
                           arguments: [failures, SyncableTimestamp.nowMilliseconds() + Int64(delay * 1000), scope])
            try db.execute(sql: "UPDATE sync_outbox SET last_error = ? WHERE scope = ? AND status = 'pending'", arguments: [message, scope])
            return delay
        }
    }

    func markSuccess(scope: String) throws {
        try database.writer.write { db in
            try requireCurrent()
            try db.execute(sql: "UPDATE sync_targets SET failure_count = 0, next_attempt_at = 0 WHERE scope = ?", arguments: [scope])
        }
    }

    static func shadow(db: Database, scope: String, table: String, id: String) throws -> RemoteRowUpdate? {
        guard let json = try String.fetchOne(db, sql: "SELECT envelope_json FROM sync_rows WHERE scope = ? AND table_name = ? AND entity_id = ?",
                                             arguments: [scope, table, id]) else { return nil }
        return try CloudSyncResponseDecoder.row(from: object(json))
    }

    static func object(_ json: String) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else {
            throw CloudAPIError.invalidResponse("快照不是对象")
        }
        return object
    }

    static func json(_ object: [String: Any]) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
    }

    private static func decodePush(_ json: String) throws -> CloudPushItem {
        let object = try object(json)
        guard let table = object["table"] as? String, let id = object["id"] as? String,
              let operation = object["operation"] as? String, let timestamp = object["updatedAtMs"] as? Int64,
              let deviceID = object["deviceId"] as? String, let snapshot = object["snapshot"] as? [String: Any],
              let mutationID = object["mutationId"] as? String, let base = object["baseVersion"] as? Int64,
              let version = object["dataVersion"] as? Int else {
            throw CloudAPIError.syncRejected("持久化的上传请求损坏")
        }
        return CloudPushItem(table: table, id: id, operation: operation, updatedAtMs: timestamp, deviceID: deviceID,
                             snapshot: snapshot, mutationID: mutationID, baseVersion: base, dataVersion: version)
    }

    private func createSafetyBackup() throws {
        guard let databaseURL = database.databaseURL else { return }
        let directory = databaseURL.deletingLastPathComponent().appending(path: "SafetyBackups", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try requireCurrent()
        let defaults = UserDefaults.standard
        let export = try AppDataTransferService(database: database).exportBackup(
            sourceDeviceID: "before-cloud-sync",
            preferences: AppDataTransferPreferences(
                languageRawValue: defaults.string(forKey: AppPreferenceStorageKeys.language),
                appearanceRawValue: defaults.string(forKey: AppPreferenceStorageKeys.appearance),
                addsPickedPhotosToMediaLibrary: defaults.bool(forKey: AppPreferenceStorageKeys.addsPickedPhotosToMediaLibrary),
                savesCameraPhotosToLibrary: defaults.object(forKey: AppPreferenceStorageKeys.savesCameraPhotosToLibrary) as? Bool ?? true,
                hiddenToolRouteIDs: defaults.stringArray(forKey: FeatureVisibilitySettings.hiddenRoutesKey) ?? []
            )
        )
        try FileManager.default.copyItem(at: export, to: directory.appending(path: "before-sync-\(UUID().uuidString).recoowbackup"))
        try FileManager.default.removeItem(at: export)
    }
}
