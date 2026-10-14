import Foundation
import GRDB

nonisolated struct MediaAsset: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "media_assets"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var kind: String
    var title: String? = nil
    var storageBackend: String
    var storageKey: String
    var mimeType: String
    var byteCount: Int
    var checksum: String
    var width: Int?
    var height: Int?
    var sortOrder: Int
    var inlineData: Data?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case kind
        case title
        case storageBackend = "storage_backend"
        case storageKey = "storage_key"
        case mimeType = "mime_type"
        case byteCount = "byte_count"
        case checksum
        case width
        case height
        case sortOrder = "sort_order"
        case inlineData = "inline_data"
    }

    static let maximumTitleLength = 50

    static func normalizedTitle(_ title: String?) -> String? {
        guard let value = title?.trimmingCharacters(in: .whitespacesAndNewlines),
              value.isEmpty == false else { return nil }
        return value
    }

    var displayTitle: String {
        Self.normalizedTitle(title) ?? AppLocalization.string("未命名素材")
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encode(updatedAt, forKey: .updatedAt)
        try container.encodeIfPresent(deletedAt, forKey: .deletedAt)
        try container.encode(syncStatus, forKey: .syncStatus)
        try container.encode(deviceID, forKey: .deviceID)
        try container.encodeIfPresent(serverVersion, forKey: .serverVersion)
        try container.encode(kind, forKey: .kind)
        // 新客户端主动清空写 null，旧客户端/升级前待同步快照仍可省略此字段。
        try container.encode(title, forKey: .title)
        try container.encode(storageBackend, forKey: .storageBackend)
        try container.encode(storageKey, forKey: .storageKey)
        try container.encode(mimeType, forKey: .mimeType)
        try container.encode(byteCount, forKey: .byteCount)
        try container.encode(checksum, forKey: .checksum)
        try container.encodeIfPresent(width, forKey: .width)
        try container.encodeIfPresent(height, forKey: .height)
        try container.encode(sortOrder, forKey: .sortOrder)
        try container.encodeIfPresent(inlineData, forKey: .inlineData)
    }

    static func makeImage(
        data: Data,
        mimeType: String,
        width: Int?,
        height: Int?,
        checksum: String,
        sortOrder: Int = 0,
        deviceID: String,
        inlineData: Data? = nil
    ) -> MediaAsset {
        let now = SyncableTimestamp.nowMilliseconds()
        let id = UUID().uuidString

        return MediaAsset(
            id: id,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            kind: "image",
            storageBackend: "local",
            storageKey: MediaAssetObjectStore.storageKey(for: id, mimeType: mimeType),
            mimeType: mimeType,
            byteCount: data.count,
            checksum: checksum,
            width: width,
            height: height,
            sortOrder: sortOrder,
            inlineData: inlineData
        )
    }
}
