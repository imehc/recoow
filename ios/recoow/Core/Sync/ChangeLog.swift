import Foundation
import GRDB

/// outbox 操作类型，后续 push 时直接映射到服务端变更协议。
enum ChangeOperation: String, Codable, Sendable {
    case insert
    case update
    case delete
}

/// change_log 表记录。payload_json 保存业务表变更快照，保证进程被杀后仍可补偿同步。
struct ChangeRecord: Identifiable, Codable, FetchableRecord, PersistableRecord, Sendable {
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
        let data = try sanitizedPayloadData(for: payload, table: table)
        let record = ChangeRecord(
            id: nil,
            entityTable: table,
            entityID: entityID,
            operation: operation,
            payloadJSON: String(decoding: data, as: UTF8.self),
            clientTimestampMilliseconds: clientTimestampMilliseconds,
            attemptCount: 0,
            lastError: nil,
            syncedAtMilliseconds: nil
        )
        try record.insert(db)
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
