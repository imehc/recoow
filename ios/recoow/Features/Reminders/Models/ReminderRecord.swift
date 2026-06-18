import Foundation
import GRDB

/// 一条本地提醒。系统通知可被用户关闭，数据库记录仍保留为离线历史。
struct ReminderRecord: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "reminders"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var note: String?
    var memoryIcon: String
    var imageData: Data?
    var scheduledAt: Int64
    var leadTimeMinutes: Int
    var isEnabled: Bool

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case title
        case note
        case memoryIcon = "memory_icon"
        case imageData = "image_data"
        case scheduledAt = "scheduled_at"
        case leadTimeMinutes = "lead_time_minutes"
        case isEnabled = "is_enabled"
    }

    var scheduledDate: Date {
        Date(timeIntervalSince1970: Double(scheduledAt) / 1000)
    }

    var leadTime: ReminderLeadTime {
        ReminderLeadTime(rawValue: leadTimeMinutes) ?? .none
    }

    var isUpcoming: Bool {
        isEnabled && deletedAt == nil && scheduledDate > Date()
    }

    static func makeNew(
        title: String,
        note: String?,
        memoryIcon: String,
        imageData: Data?,
        scheduledAt: Int64,
        leadTimeMinutes: Int,
        deviceID: String
    ) -> ReminderRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        return ReminderRecord(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            note: note,
            memoryIcon: memoryIcon,
            imageData: imageData,
            scheduledAt: scheduledAt,
            leadTimeMinutes: leadTimeMinutes,
            isEnabled: true
        )
    }
}
