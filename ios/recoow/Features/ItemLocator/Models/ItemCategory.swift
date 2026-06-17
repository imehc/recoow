import Foundation
import GRDB

/// 物品分类，例如“证件”“工具”“数码配件”。
struct ItemCategory: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "item_categories"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var name: String
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
        case note
    }

    static func makeNew(name: String, note: String?, deviceID: String) -> ItemCategory {
        let now = SyncableTimestamp.nowMilliseconds()
        return ItemCategory(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            name: name,
            note: note
        )
    }
}
