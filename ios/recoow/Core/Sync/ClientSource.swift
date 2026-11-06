import Foundation
import GRDB

nonisolated enum ClientSource {
    static var environment: [String: String] {
        let system = ProcessInfo.processInfo.operatingSystemVersion
        return ["client": "ios", "os": "ios",
                "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
                "osVersion": "\(system.majorVersion).\(system.minorVersion).\(system.patchVersion)"]
    }

    static func origin(deviceID: String, localDeviceID: String, deviceName: String = "iOS 设备") -> String? {
        guard deviceID == localDeviceID else { return nil }
        var result = environment
        result["deviceId"] = deviceID
        result["deviceName"] = String(deviceName.prefix(160))
        guard let data = try? JSONEncoder().encode(result) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    static func save(db: Database, table: String, id: String, snapshot: [String: Any], originJSON: String?) throws {
        guard let timestamp = snapshot["updated_at"] as? Int64, let device = snapshot["device_id"] as? String else { return }
        try db.execute(sql: "INSERT OR REPLACE INTO record_origins (table_name, entity_id, device_id, updated_at, origin_json) VALUES (?, ?, ?, ?, ?)",
                       arguments: [table, id, device, timestamp, originJSON])
    }

    static func stored(db: Database, table: String, id: String, snapshot: [String: Any]) throws -> String? {
        guard let timestamp = snapshot["updated_at"] as? Int64, let device = snapshot["device_id"] as? String else { return nil }
        return try String.fetchOne(db, sql: "SELECT origin_json FROM record_origins WHERE table_name = ? AND entity_id = ? AND device_id = ? AND updated_at = ?",
                                   arguments: [table, id, device, timestamp])
    }
}
