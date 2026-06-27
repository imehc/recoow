import Foundation
import GRDB

/// 一条物品位置记录。
struct StoredItem: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord, MutableStandardImageReferenceProviding {
    static let databaseTableName = "stored_items"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var categoryID: String?
    var title: String
    var location: String
    var note: String?
    var tags: String?
    var searchKeywords: String?
    var imageData: Data?
    var imageAssetID: String?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case categoryID = "category_id"
        case title
        case location
        case note
        case tags
        case searchKeywords = "search_keywords"
        case imageData = "image_data"
        case imageAssetID = "image_asset_id"
    }

    static func makeNew(
        categoryID: String?,
        title: String,
        location: String,
        note: String?,
        tags: String?,
        searchKeywords: String?,
        imageData: Data?,
        imageAssetID: String? = nil,
        deviceID: String
    ) -> StoredItem {
        let now = SyncableTimestamp.nowMilliseconds()
        return StoredItem(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            categoryID: categoryID,
            title: title,
            location: location,
            note: note,
            tags: tags,
            searchKeywords: searchKeywords,
            imageData: imageData,
            imageAssetID: imageAssetID
        )
    }
}
