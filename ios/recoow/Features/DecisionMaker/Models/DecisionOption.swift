import Foundation
import GRDB

/// 随机选择集合中的一个候选项。
struct DecisionOption: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "decision_options"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var collectionID: String
    var title: String
    var detail: String?
    var customInfo: String?
    var imageData: Data?
    var weight: Int
    var isEnabled: Bool

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case collectionID = "collection_id"
        case title
        case detail
        case customInfo = "custom_info"
        case imageData = "image_data"
        case weight
        case isEnabled = "is_enabled"
    }

    static func makeNew(
        collectionID: String,
        title: String,
        detail: String?,
        customInfo: String?,
        imageData: Data?,
        weight: Int,
        isEnabled: Bool,
        deviceID: String
    ) -> DecisionOption {
        let now = SyncableTimestamp.nowMilliseconds()
        return DecisionOption(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            collectionID: collectionID,
            title: title,
            detail: detail,
            customInfo: customInfo,
            imageData: imageData,
            weight: max(1, weight),
            isEnabled: isEnabled
        )
    }
}
