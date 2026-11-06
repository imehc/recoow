import CoreFoundation
import Foundation

nonisolated enum CloudSyncResponseDecoder {
    static func push(_ data: Data, items: [CloudPushItem]) throws -> CloudPushResponse {
        let object = try object(from: data)
        guard let results = object["results"] as? [[String: Any]], results.count == items.count else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("回执数量与请求不一致"))
        }

        let receipts = try zip(results, items).map { result, item in
            let table = try string("table", in: result)
            let id = try string("id", in: result)
            let operation = try string("operation", in: result)
            guard table == item.table, id == item.id, operation == item.operation else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("回执顺序或记录标识与请求不一致"))
            }
            if !item.mutationID.isEmpty, (result["mutationId"] as? String) != item.mutationID {
                throw CloudAPIError.invalidResponse(AppLocalization.string("mutationId 与请求不一致"))
            }
            guard let rawStatus = result["status"] as? String,
                  let status = CloudPushStatus(rawValue: rawStatus) else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("缺少受支持的回执状态，请先更新后端"))
            }
            let applied = try boolean("applied", in: result)
            let version = try integer("version", in: result)
            guard applied == (status == .applied), version >= 0 else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("回执状态与版本不一致"))
            }

            var current: RemoteRowUpdate?
            if let rawCurrent = result["current"], !(rawCurrent is NSNull) {
                guard let currentObject = rawCurrent as? [String: Any] else {
                    throw CloudAPIError.invalidResponse(AppLocalization.string("冲突当前行格式错误"))
                }
                current = try row(from: currentObject)
                guard current?.table == table, current?.id == id, current?.version == version else {
                    throw CloudAPIError.invalidResponse(AppLocalization.string("冲突当前行与回执不一致"))
                }
            }

            switch status {
            case .applied:
                guard version > 0 else {
                    throw CloudAPIError.invalidResponse(AppLocalization.string("已应用回执缺少版本"))
                }
            case .duplicate, .conflict:
                guard version > 0, current != nil else {
                    throw CloudAPIError.invalidResponse(AppLocalization.string("重放或冲突回执缺少当前行"))
                }
            case .unsupportedTable, .invalidOperation, .mutationReused, .dependencyMissing, .invalidRevision:
                break
            }

            return CloudPushResult(
                table: table, id: id, applied: applied, operation: operation,
                version: version, status: status, current: current, mutationID: item.mutationID
            )
        }
        return CloudPushResponse(results: receipts)
    }

    static func pull(_ data: Data, after requestedCursor: Int64) throws -> CloudPullResult {
        let object = try object(from: data)
        guard let rawEntries = object["entries"] as? [[String: Any]] else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("缺少变更列表"))
        }
        let cursor = try integer("cursor", in: object)
        let more = try boolean("more", in: object)
        var previousVersion = requestedCursor
        let entries = try rawEntries.map { rawEntry in
            let update = try row(from: rawEntry)
            let rawOperation = try string("operation", in: rawEntry)
            let deleted = try boolean("deleted", in: rawEntry)
            guard let operation = ChangeOperation(rawValue: rawOperation),
                  deleted == (operation == .delete), deleted == update.isDeleted,
                  update.version > previousVersion else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("变更操作、删除标记或版本顺序错误"))
            }
            previousVersion = update.version
            return CloudChange(
                version: update.version, table: update.table, id: update.id,
                operation: operation, updatedAtMs: update.updatedAtMs, deviceID: update.deviceID,
                deleted: deleted, deletedAtMs: update.deletedAtMs, snapshot: update.snapshot, dataVersion: update.dataVersion
            )
        }
        guard cursor == previousVersion, cursor >= requestedCursor, !more || !entries.isEmpty else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("游标与变更列表不一致"))
        }
        return CloudPullResult(entries: entries, cursor: cursor, more: more)
    }

    static func row(from object: [String: Any]) throws -> RemoteRowUpdate {
        let object = try APIFields.object(object)
        let table = try string("table", in: object)
        let id = try string("id", in: object)
        let version = try integer("version", in: object)
        let updatedAt = try integer("updatedAtMs", in: object)
        let deviceID = try string("deviceId", in: object)
        var deletedAt: Int64?
        if let rawDeletedAt = object["deletedAtMs"], !(rawDeletedAt is NSNull) {
            let timestamp = try integer("deletedAtMs", in: object)
            guard timestamp >= 0 else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("删除时间无效"))
            }
            if timestamp > 0 { deletedAt = timestamp }
        }
        guard version > 0, updatedAt > 0, deletedAt == nil || deletedAt == updatedAt else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("行版本或时间无效"))
        }
        let snapshot = object["snapshot"] as? [String: Any]
        if deletedAt == nil {
            guard let snapshot else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("存活行缺少快照"))
            }
            for (field, expected) in [("id", id), ("device_id", deviceID)] where snapshot[field] != nil {
                guard try string(field, in: snapshot) == expected else {
                    throw CloudAPIError.invalidResponse(AppLocalization.format("快照 %@ 与信封不一致", field))
                }
            }
            if snapshot["updated_at"] != nil, try integer("updated_at", in: snapshot) != updatedAt {
                throw CloudAPIError.invalidResponse(AppLocalization.string("快照更新时间与信封不一致"))
            }
            if let timestamp = snapshot["deleted_at"], !(timestamp is NSNull) {
                throw CloudAPIError.invalidResponse(AppLocalization.string("存活行快照包含删除标记"))
            }
        }
        return RemoteRowUpdate(
            table: table, id: id, version: version, updatedAtMs: updatedAt,
            deviceID: deviceID, deletedAtMs: deletedAt, snapshot: snapshot,
            dataVersion: object["dataVersion"] == nil ? 1 : Int(try integer("dataVersion", in: object))
        )
    }

    private static func object(from data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("响应不是 JSON 对象"))
        }
        return try APIFields.object(object)
    }

    private static func string(_ field: String, in object: [String: Any]) throws -> String {
        guard let value = object[field] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CloudAPIError.invalidResponse(AppLocalization.format("%@ 必须是非空字符串", field))
        }
        return value
    }

    private static func integer(_ field: String, in object: [String: Any]) throws -> Int64 {
        guard let number = object[field] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              let value = Int64(number.stringValue) else {
            throw CloudAPIError.invalidResponse(AppLocalization.format("%@ 必须是整数", field))
        }
        return value
    }

    private static func boolean(_ field: String, in object: [String: Any]) throws -> Bool {
        guard let number = object[field] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
            throw CloudAPIError.invalidResponse(AppLocalization.format("%@ 必须是布尔值", field))
        }
        return number.boolValue
    }
}
