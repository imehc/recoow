import Foundation
import GRDB

/// outbox 操作类型，后续 push 时直接映射到服务端变更协议。
enum ChangeOperation: String, Codable, Sendable {
    case insert
    case update
    case delete
}

/// change_log 表记录。payload_json 保存业务表变更快照，保证进程被杀后仍可补偿同步。
nonisolated struct ChangeRecord: Identifiable, Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "change_log"

    var id: Int64?
    var entityTable: String
    var entityID: String
    var operation: ChangeOperation
    var payloadJSON: String
    var clientTimestampMilliseconds: Int64
    var attemptCount: Int
    var lastError: String?
    var syncedAtMilliseconds: Int64?
    var mutationID: String = UUID().uuidString

    enum CodingKeys: String, CodingKey {
        case id
        case entityTable = "entity_table"
        case entityID = "entity_id"
        case operation
        case payloadJSON = "payload_json"
        case clientTimestampMilliseconds = "client_ts_ms"
        case attemptCount = "attempt_count"
        case lastError = "last_error"
        case syncedAtMilliseconds = "synced_at_ms"
        case mutationID = "mutation_id"
    }
}

/// push 被服务端采纳后，需要回写业务行的一小段确认信息。
struct RowSyncAck: Sendable {
    var table: String
    var entityID: String
    var changeLogID: Int64
    var serverVersion: Int64
    var canonicalRow: RemoteRowUpdate?
    var resolutionMessage: String?
}

/// pull 从服务端拉取到的一条待本地应用的变更。
nonisolated struct RemoteRowUpdate: @unchecked Sendable {
    var table: String
    var id: String
    var version: Int64
    var updatedAtMs: Int64
    var deviceID: String
    var deletedAtMs: Int64?
    var snapshot: [String: Any]?
    var acknowledgedChangeLogID: Int64? = nil
    var dataVersion: Int = 1

    var envelope: [String: Any] {
        var result: [String: Any] = ["table": table, "id": id, "version": version, "updatedAtMs": updatedAtMs,
                                     "deviceId": deviceID, "dataVersion": dataVersion]
        result["snapshot"] = snapshot
        result["deletedAtMs"] = deletedAtMs
        return result
    }

    var isDeleted: Bool { deletedAtMs != nil }

    func decodeRecord<Record: Decodable>(_ type: Record.Type) throws -> Record {
        guard var object = snapshot, !isDeleted else {
            throw CloudAPIError.invalidResponse(AppLocalization.format("%@/%@ 缺少存活行快照", table, id))
        }
        object["id"] = id
        object["updated_at"] = updatedAtMs
        object["device_id"] = deviceID
        object["deleted_at"] = NSNull()
        object["sync_status"] = SyncStatus.synced.rawValue
        object["server_version"] = version
        do {
            let data = try JSONSerialization.data(withJSONObject: object)
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw CloudAPIError.invalidResponse(AppLocalization.format("%@/%@ 无法解码：%@", table, id, error.localizedDescription))
        }
    }
}

/// change_log 读写封装。Repository 在业务事务内调用 append，保证原子性。
final class ChangeLogRepository: @unchecked Sendable {
    private let database: AppDatabase
    private let encoder: JSONEncoder

    init(database: AppDatabase) {
        self.database = database
        encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
    }

    func append<T: Encodable>(
        db: Database,
        table: String,
        entityID: String,
        operation: ChangeOperation,
        payload: T,
        clientTimestampMilliseconds: Int64
    ) throws {
        let rawData = try encoder.encode(payload)
        let scopes = try String.fetchAll(db, sql: "SELECT scope FROM sync_targets WHERE seeded = 1")
        var captureError: String?
        let data: Data
        if scopes.isEmpty {
            data = try Self.sanitizedPayloadData(rawData, table: table) ?? rawData
        } else {
            do {
                data = try SyncMediaTransfer.capturePayload(rawData, table: table)
            } catch {
                data = rawData
                captureError = error.localizedDescription
            }
        }
        let record = ChangeRecord(
            id: nil,
            entityTable: table,
            entityID: entityID,
            operation: operation,
            payloadJSON: String(decoding: data, as: UTF8.self),
            clientTimestampMilliseconds: clientTimestampMilliseconds,
            attemptCount: 0,
            lastError: captureError,
            syncedAtMilliseconds: nil
        )
        try record.insert(db)
        let changeID = db.lastInsertedRowID
        for scope in scopes {
            try FullSyncRepository.enqueue(db: db, scope: scope, table: table, entityID: entityID,
                                           operation: operation, payloadJSON: record.payloadJSON, localChangeID: changeID,
                                           capturePending: captureError != nil)
        }
    }

    func fetchPending(limit: Int = 100) throws -> [ChangeRecord] {
        try database.reader.read { db in
            try ChangeRecord
                .filter(Column("synced_at_ms") == nil)
                .order(Column("client_ts_ms").asc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    /// 统计当前仍待同步（未确认）的 outbox 记录条数。
    func countPending() throws -> Int {
        try database.reader.read { db in
            try ChangeRecord.filter(Column("synced_at_ms") == nil).fetchCount(db)
        }
    }

    /// 只取指定启用表里仍待同步的记录（同步范围之外的板块留在 outbox，等能力开放后再上送）。
    func fetchPending(tables: Set<String>, limit: Int = 100) throws -> [ChangeRecord] {
        let tableList = tables.sorted()
        guard tableList.isEmpty == false else { return [] }
        return try database.reader.read { db in
            try ChangeRecord
                .filter(Column("synced_at_ms") == nil)
                .filter(tableList.contains(Column("entity_table")))
                .order(Column("client_ts_ms").asc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    /// 统计启用表范围内仍待同步的记录条数。
    func countPending(tables: Set<String>) throws -> Int {
        let tableList = tables.sorted()
        guard tableList.isEmpty == false else { return 0 }
        return try database.reader.read { db in
            try ChangeRecord
                .filter(Column("synced_at_ms") == nil)
                .filter(tableList.contains(Column("entity_table")))
                .fetchCount(db)
        }
    }

    func acknowledge(
        _ acks: [RowSyncAck],
        syncedAtMilliseconds: Int64,
        allowedTables: Set<String>,
        applyRemote: (Database, RemoteRowUpdate) throws -> Bool
    ) throws {
        guard acks.isEmpty == false else { return }
        try database.writer.write { db in
            for ack in acks {
                guard allowedTables.contains(ack.table) else {
                    throw CloudAPIError.invalidResponse(AppLocalization.format("不支持确认实体 %@", ack.table))
                }
                if var canonicalRow = ack.canonicalRow {
                    canonicalRow.acknowledgedChangeLogID = ack.changeLogID
                    _ = try applyRemote(db, canonicalRow)
                }
                try db.execute(
                    sql: "UPDATE change_log SET synced_at_ms = ?, last_error = ? WHERE id = ?",
                    arguments: [syncedAtMilliseconds, ack.resolutionMessage, ack.changeLogID]
                )
                try db.execute(
                    sql: """
                        UPDATE "\(ack.table)"
                        SET server_version = MAX(COALESCE(server_version, 0), ?)
                        WHERE id = ?
                        """,
                    arguments: [ack.serverVersion, ack.entityID]
                )
                let stillPending = try Int.fetchOne(
                    db,
                    sql: "SELECT COUNT(*) FROM change_log WHERE entity_table = ? AND entity_id = ? AND synced_at_ms IS NULL",
                    arguments: [ack.table, ack.entityID]
                ) ?? 0
                if stillPending == 0 {
                    try db.execute(
                        sql: "UPDATE \"\(ack.table)\" SET sync_status = 1 WHERE id = ?",
                        arguments: [ack.entityID]
                    )
                }
            }
        }
    }

    func applyRemotePage(
        _ updates: [RemoteRowUpdate],
        allowedTables: Set<String>,
        applyRemote: (Database, RemoteRowUpdate) throws -> Bool
    ) throws -> Int {
        try database.writer.write { db in
            var appliedCount = 0
            for update in updates {
                guard allowedTables.contains(update.table) else {
                    throw CloudAPIError.invalidResponse(AppLocalization.format("本客户端尚不支持实体 %@，已保留同步游标", update.table))
                }
                if try applyRemote(db, update) { appliedCount += 1 }
            }
            return appliedCount
        }
    }

    func remoteWins<Record: SyncableRecord>(
        db: Database,
        update: RemoteRowUpdate,
        over existing: Record
    ) throws -> Bool {
        if update.updatedAtMs != existing.updatedAt { return update.updatedAtMs > existing.updatedAt }
        if update.deviceID != existing.deviceID { return update.deviceID > existing.deviceID }
        if update.version < (existing.serverVersion ?? 0) { return false }
        if update.version == existing.serverVersion, existing.syncStatus == .synced { return false }

        let newerPendingCount = try Int.fetchOne(
            db,
            sql: """
                SELECT COUNT(*) FROM change_log
                WHERE entity_table = ? AND entity_id = ? AND synced_at_ms IS NULL AND id > ?
                """,
            arguments: [update.table, update.id, update.acknowledgedChangeLogID ?? 0]
        ) ?? 0
        return newerPendingCount == 0
    }

    func resolvePending(db: Database, table: String, entityID: String, serverVersion: Int64) throws {
        try db.execute(
            sql: """
                UPDATE change_log SET synced_at_ms = ?, last_error = ?
                WHERE entity_table = ? AND entity_id = ? AND synced_at_ms IS NULL
                """,
            arguments: [
                SyncableTimestamp.nowMilliseconds(),
                AppLocalization.format("服务端状态已确认或取代此变更（版本 %d）", serverVersion),
                table, entityID
            ]
        )
    }

    func recordFailedAttempts(changeLogIDs: [Int64], message: String) throws {
        guard !changeLogIDs.isEmpty else { return }
        try database.writer.write { db in
            for changeID in changeLogIDs {
                try db.execute(
                    sql: """
                        UPDATE change_log SET attempt_count = attempt_count + 1, last_error = ?
                        WHERE id = ? AND synced_at_ms IS NULL
                        """,
                    arguments: [message, changeID]
                )
            }
        }
    }

    private func sanitizedPayloadData<T: Encodable>(for payload: T, table: String) throws -> Data {
        let data = try encoder.encode(payload)
        return try Self.sanitizedPayloadData(data, table: table) ?? data
    }

    nonisolated static func sanitizedPayloadJSON(_ json: String, table: String) throws -> String? {
        guard let data = json.data(using: .utf8),
              let sanitizedData = try sanitizedPayloadData(data, table: table)
        else {
            return nil
        }

        return String(decoding: sanitizedData, as: UTF8.self)
    }

    nonisolated private static func sanitizedPayloadData(_ data: Data, table: String) throws -> Data? {
        guard let omittedKeys = Self.omittedBinaryPayloadKeysByTable[table],
              omittedKeys.isEmpty == false,
              var object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return nil
        }

        var didOmitBinaryPayload = false
        for key in omittedKeys where object.removeValue(forKey: key) != nil {
            didOmitBinaryPayload = true
        }

        guard didOmitBinaryPayload else {
            return nil
        }

        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    nonisolated static let omittedBinaryPayloadKeysByTable: [String: Set<String>] = [
        "media_attachments": ["data"],
        "media_assets": ["inline_data"],
        "bills": ["image_data"],
        "reminders": ["image_data"],
        "stored_items": ["image_data"],
        "decision_options": ["image_data"],
        "decision_choice_records": ["option_image_data"]
    ]
}
