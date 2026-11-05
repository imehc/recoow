import CryptoKit
import Foundation
import GRDB

nonisolated struct SyncMediaReference: Codable, Sendable {
    let key: String
    let checksum: String
    let size: Int64
    let contentType: String

    var object: [String: Any] { ["key": key, "checksum": checksum, "size": size, "contentType": contentType] }

    init(data: Data, contentType: String) {
        checksum = SyncMediaTransfer.checksum(data)
        key = "sync/" + checksum
        size = Int64(data.count)
        self.contentType = contentType
    }

    init(object: [String: Any]) throws {
        guard let key = object["key"] as? String, let checksum = object["checksum"] as? String,
              let size = object["size"] as? Int64, let contentType = object["contentType"] as? String,
              checksum.count == 64, checksum.allSatisfy({ $0.isHexDigit && !$0.isUppercase }),
              key == "sync/" + checksum, size >= 0, size <= 200 * 1024 * 1024 else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("媒体引用无效"))
        }
        self.key = key
        self.checksum = checksum
        self.size = size
        self.contentType = contentType
    }

    func verify(_ data: Data) throws {
        guard data.count == size, SyncMediaTransfer.checksum(data) == checksum else {
            throw CloudAPIError.syncRejected(AppLocalization.format("媒体完整性校验失败：%@", key))
        }
    }
}

nonisolated final class SyncMediaTransfer: @unchecked Sendable {
    private let database: AppDatabase

    init(database: AppDatabase) { self.database = database }

    static func checksum(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func capturePayload(_ data: Data, table: String) throws -> Data {
        let entity = try SyncEntityCatalog.entity(table)
        var object = try FullSyncRepository.object(String(decoding: data, as: UTF8.self))
        var references = object["_media"] as? [String: Any] ?? [:]
        let deleted = object["deleted_at"].map { !($0 is NSNull) } ?? false
        let contentType = object["mime_type"] as? String ?? "image/jpeg"
        for field in entity.fields where field.binary {
            if let encoded = object[field.name] as? String, let bytes = Data(base64Encoded: encoded), !bytes.isEmpty, !deleted {
                let reference = SyncMediaReference(data: bytes, contentType: contentType)
                guard bytes.count <= 200 * 1024 * 1024 else { throw CloudAPIError.syncRejected(AppLocalization.string("媒体超过 200 MiB")) }
                try MediaAssetObjectStore.shared.write(bytes, storageKey: reference.key)
                references[field.name] = reference.object
            } else if object[field.name] == nil || object[field.name] is NSNull || object[field.name] as? String == "" {
                references[field.name] = NSNull()
            }
            object.removeValue(forKey: field.name)
        }
        if table == "media_assets", !deleted {
            guard let storageKey = object["storage_key"] as? String,
                  let bytes = MediaAssetObjectStore.shared.data(for: storageKey) else {
                throw CloudAPIError.syncRejected(AppLocalization.string("素材文件缺失，无法生成完整备份"))
            }
            let reference = SyncMediaReference(data: bytes, contentType: contentType)
            guard object["checksum"] as? String == reference.checksum, object["byte_count"] as? Int64 == reference.size else {
                throw CloudAPIError.syncRejected(AppLocalization.string("素材元数据与实际文件不一致"))
            }
            try MediaAssetObjectStore.shared.write(bytes, storageKey: reference.key)
            references["object"] = reference.object
            references.removeValue(forKey: "inline_data")
            object["storage_key"] = reference.key
        }
        for field in entity.fields where field.nullable && !field.binary && object[field.name] == nil {
            // 升级前排队的素材快照没有 title，不能将缺失补为主动清空。
            if table == "media_assets", field.name == "title" { continue }
            object[field.name] = NSNull()
        }
        if !references.isEmpty { object["_media"] = references }
        object.removeValue(forKey: "sync_status")
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    static func queueUploads(db: Database, scope: String, item: CloudPushItem) throws {
        if item.operation == "delete" { return }
        try queue(db: db, scope: scope, direction: "upload", table: item.table, id: item.id, snapshot: item.snapshot, version: 0)
    }

    static func queueDownloads(db: Database, scope: String, update: RemoteRowUpdate) throws {
        guard let snapshot = update.snapshot else { return }
        try queue(db: db, scope: scope, direction: "download", table: update.table, id: update.id, snapshot: snapshot, version: update.version)
    }

    private static func queue(db: Database, scope: String, direction: String, table: String, id: String,
                              snapshot: [String: Any], version: Int64) throws {
        let entity = try SyncEntityCatalog.entity(table)
        guard let references = snapshot["_media"] as? [String: Any] else { return }
        for (field, raw) in references {
            guard entity.fields.contains(where: { $0.name == field && $0.binary }) || (field == "object" && table == "media_assets") else {
                throw CloudAPIError.invalidResponse(AppLocalization.format("未知媒体字段 %@.%@", table, field))
            }
            if raw is NSNull {
                if direction == "download", field != "object" {
                    let value = entity.fields.first(where: { $0.name == field })?.nullable == true ? DatabaseValue.null : Data().databaseValue
                    try db.execute(sql: "UPDATE \"\(table)\" SET \"\(field)\" = ? WHERE id = ?", arguments: [value, id])
                }
                continue
            }
            guard let object = raw as? [String: Any] else { throw CloudAPIError.invalidResponse(AppLocalization.string("媒体描述格式错误")) }
            let reference = try SyncMediaReference(object: object)
            let storageKey = field == "object" ? snapshot["storage_key"] as? String : nil
            try db.execute(sql: """
                INSERT INTO sync_media_jobs (scope, direction, object_key, checksum, size, content_type, table_name,
                    entity_id, column_name, storage_key, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scope, direction, table_name, entity_id, column_name, checksum)
                DO UPDATE SET version = excluded.version, storage_key = excluded.storage_key,
                    state = CASE WHEN sync_media_jobs.direction = 'download' THEN 'pending' ELSE sync_media_jobs.state END
                """, arguments: [scope, direction, reference.key, reference.checksum, reference.size, reference.contentType,
                                   table, id, field, storageKey, version])
        }
    }

    private static func install(db: Database, table: String, id: String, field: String, objectKey: String, data: Data) throws {
        if field == "object" {
            try db.execute(sql: "UPDATE media_assets SET storage_key = ?, inline_data = NULL WHERE id = ?", arguments: [objectKey, id])
        } else {
            try db.execute(sql: "UPDATE \"\(table)\" SET \"\(field)\" = ? WHERE id = ?", arguments: [data, id])
        }
        try SyncRowApplier.queueNotification(db: db, table: table, id: id, version: SyncableTimestamp.nowMilliseconds())
    }

    func transfer(scope: String, direction: String, api: RecoowAPIClient, isCurrent: @escaping @Sendable () -> Bool,
                  onProgress: (@Sendable (Int, Int) async -> Void)? = nil) async throws -> Int {
        let jobs = try await database.reader.read { db in
            try Row.fetchAll(db, sql: "SELECT * FROM sync_media_jobs WHERE scope = ? AND direction = ? AND state = 'pending' ORDER BY id",
                             arguments: [scope, direction])
        }
        var count = 0
        var handled = 0
        for job in jobs {
            guard isCurrent() else { throw CloudAPIError.syncRejected(AppLocalization.string("连接已改变，已停止媒体传输")) }
            let identifier: Int64 = job["id"]
            let table: String = job["table_name"]
            let entityID: String = job["entity_id"]
            let field: String = job["column_name"]
            let reference = try SyncMediaReference(object: ["key": job["object_key"] as String, "checksum": job["checksum"] as String,
                                                           "size": job["size"] as Int64, "contentType": job["content_type"] as String])
            do {
                let bytes: Data
                if direction == "upload" {
                    guard let data = MediaAssetObjectStore.shared.data(for: reference.key) else {
                        throw CloudAPIError.syncRejected(AppLocalization.format("待上传媒体文件缺失：%@", reference.key))
                    }
                    try reference.verify(data)
                    try await api.uploadMedia(data, reference: reference)
                    bytes = data
                } else if let data = MediaAssetObjectStore.shared.data(for: reference.key), (try? reference.verify(data)) != nil {
                    bytes = data
                } else {
                    bytes = try await api.downloadMedia(reference: reference)
                    try reference.verify(bytes)
                    try MediaAssetObjectStore.shared.write(bytes, storageKey: reference.key)
                }
                guard isCurrent() else { throw CloudAPIError.syncRejected(AppLocalization.string("连接已改变，保留媒体任务等待下次同步")) }
                try await database.writer.write { db in
                    guard isCurrent() else { throw CloudAPIError.syncRejected(AppLocalization.string("连接或本地数据库已改变，未写入媒体")) }
                    if direction == "download", let shadow = try FullSyncRepository.shadow(db: db, scope: scope, table: table, id: entityID),
                       !shadow.isDeleted,
                       let current = (shadow.snapshot?["_media"] as? [String: Any])?[field] as? [String: Any],
                       current["checksum"] as? String == reference.checksum {
                        let pending = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = ? AND status IN ('pending', 'conflict')",
                                                       arguments: [scope, table, entityID]) ?? 0
                        if pending == 0 {
                            try Self.install(db: db, table: table, id: entityID, field: field, objectKey: reference.key, data: bytes)
                        }
                    }
                    try db.execute(sql: "UPDATE sync_media_jobs SET state = 'done', last_error = NULL WHERE id = ?", arguments: [identifier])
                }
                count += 1
            } catch {
                try await database.writer.write { db in
                    guard isCurrent() else { return }
                    try db.execute(sql: """
                        UPDATE sync_media_jobs SET attempt_count = attempt_count + 1, next_attempt_at = ?, last_error = ? WHERE id = ?
                        """, arguments: [SyncableTimestamp.nowMilliseconds() + 2000, error.localizedDescription, identifier])
                }
                throw error
            }
            handled += 1
            if let onProgress {
                await onProgress(handled, jobs.count)
            }
        }
        return count
    }
}
