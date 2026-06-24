import Foundation
import GRDB

struct FoodDayRecord: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "food_day_records"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var dayStartAt: Int64

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case title
        case dayStartAt = "day_start_at"
    }

    nonisolated var normalizedTitle: String? {
        let value = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    static func makeNew(title: String, dayStartAt: Int64, deviceID: String) -> FoodDayRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        return FoodDayRecord(
            id: stableID(dayStartAt: dayStartAt),
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            dayStartAt: dayStartAt
        )
    }

    static func stableID(dayStartAt: Int64) -> String {
        "food-day:\(dayStartAt)"
    }
}
