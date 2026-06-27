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
    var storageBackend: String
    var storageKey: String
    var mimeType: String
    var byteCount: Int
    var checksum: String
    var width: Int?
    var height: Int?
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
        case storageBackend = "storage_backend"
        case storageKey = "storage_key"
        case mimeType = "mime_type"
        case byteCount = "byte_count"
        case checksum
        case width
        case height
        case inlineData = "inline_data"
    }

    static func makeImage(
        data: Data,
        mimeType: String,
        width: Int?,
        height: Int?,
        checksum: String,
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
            inlineData: inlineData
        )
    }
}
