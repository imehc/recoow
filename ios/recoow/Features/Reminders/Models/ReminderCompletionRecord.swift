import Foundation
import GRDB

nonisolated struct ReminderCompletionRecord: Codable, FetchableRecord, PersistableRecord, SyncableRecord, Sendable {
    static let databaseTableName = "reminder_completions"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?
    var reminderID: String
    var dateKey: String
    var completedAt: Int64
    var kind: String
    var note: String?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case reminderID = "reminder_id"
        case dateKey = "date_key"
        case completedAt = "completed_at"
        case kind
        case note
    }
}
