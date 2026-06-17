import Foundation
import GRDB

/// 一次轨迹记录会话。
struct Track: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "tracks"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var name: String
    var startedAt: Int64
    var endedAt: Int64?
    var desiredAccuracyMeters: Int
    var distanceMeters: Double
    var durationSeconds: Int64
    var averageSpeedMetersPerSecond: Double?
    var maxSpeedMetersPerSecond: Double?
    var note: String?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case name
        case startedAt = "started_at"
        case endedAt = "ended_at"
        case desiredAccuracyMeters = "desired_accuracy_m"
        case distanceMeters = "distance_m"
        case durationSeconds = "duration_s"
        case averageSpeedMetersPerSecond = "avg_speed_mps"
        case maxSpeedMetersPerSecond = "max_speed_mps"
        case note
    }

    static func makeNew(accuracy: LocationAccuracy, deviceID: String) -> Track {
        let now = nowMilliseconds()
        return Track(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            name: "轨迹 \(DateFormatter.trackName.string(from: Date()))",
            startedAt: now,
            endedAt: nil,
            desiredAccuracyMeters: accuracy.rawValue,
            distanceMeters: 0,
            durationSeconds: 0,
            averageSpeedMetersPerSecond: nil,
            maxSpeedMetersPerSecond: nil,
            note: nil
        )
    }
}

private extension DateFormatter {
    static let trackName: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_Hans_CN")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter
    }()
}
