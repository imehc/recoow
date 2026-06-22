import Foundation
import GRDB

struct MediaAttachment: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "media_attachments"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var ownerType: String
    var ownerID: String
    var kindRawValue: String
    var title: String?
    var data: Data
    var mimeType: String
    var durationSeconds: Double?
    var width: Int?
    var height: Int?
    var sortOrder: Int

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case ownerType = "owner_type"
        case ownerID = "owner_id"
        case kindRawValue = "kind"
        case title
        case data
        case mimeType = "mime_type"
        case durationSeconds = "duration_seconds"
        case width
        case height
        case sortOrder = "sort_order"
    }

    var owner: MediaAttachmentOwnerType {
        MediaAttachmentOwnerType(rawValue: ownerType) ?? .diary
    }

    var kind: MediaAttachmentKind {
        MediaAttachmentKind(rawValue: kindRawValue) ?? .photo
    }

    var displayTitle: String {
        let normalizedTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let normalizedTitle, normalizedTitle.isEmpty == false {
            return normalizedTitle
        }

        return AppLocalization.string(kind.title)
    }

    var detailText: String {
        switch kind {
        case .photo:
            return AppLocalization.string("图片附件")
        case .audio:
            if let durationSeconds {
                return AppLocalization.format("语音 %@",
                                              Self.formatDuration(durationSeconds))
            }

            return AppLocalization.string("语音附件")
        }
    }

    static func makeNew(
        ownerType: MediaAttachmentOwnerType,
        ownerID: String,
        kind: MediaAttachmentKind,
        title: String?,
        data: Data,
        mimeType: String,
        durationSeconds: Double? = nil,
        width: Int? = nil,
        height: Int? = nil,
        deviceID: String
    ) -> MediaAttachment {
        let now = SyncableTimestamp.nowMilliseconds()
        return MediaAttachment(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            ownerType: ownerType.rawValue,
            ownerID: ownerID,
            kindRawValue: kind.rawValue,
            title: title,
            data: data,
            mimeType: mimeType,
            durationSeconds: durationSeconds,
            width: width,
            height: height,
            sortOrder: 0
        )
    }

    func normalized(ownerType: MediaAttachmentOwnerType, ownerID: String) -> MediaAttachment {
        var copy = self
        copy.ownerType = ownerType.rawValue
        copy.ownerID = ownerID
        copy.kindRawValue = kind.rawValue
        return copy
    }

    static func formatDuration(_ seconds: Double) -> String {
        let totalSeconds = max(0, Int(seconds.rounded()))
        let minutes = totalSeconds / 60
        let remainingSeconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, remainingSeconds)
    }
}
