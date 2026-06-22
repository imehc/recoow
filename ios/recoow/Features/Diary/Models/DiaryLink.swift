import Foundation
import GRDB

struct DiaryLink: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "diary_links"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var diaryID: String
    var sourceType: String
    var sourceID: String
    var sourceTitle: String
    var sourceSubtitle: String?
    var sourceIcon: String
    var sourceOccurredAt: Int64?
    var snapshotJSON: String?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case diaryID = "diary_id"
        case sourceType = "source_type"
        case sourceID = "source_id"
        case sourceTitle = "source_title"
        case sourceSubtitle = "source_subtitle"
        case sourceIcon = "source_icon"
        case sourceOccurredAt = "source_occurred_at"
        case snapshotJSON = "snapshot_json"
    }

    var type: DiaryLinkSourceType {
        DiaryLinkSourceType(rawValue: sourceType) ?? .track
    }

    var sourceKey: String {
        "\(sourceType):\(sourceID)"
    }

    static func makeNew(
        diaryID: String,
        record: DiaryLinkedRecord,
        deviceID: String
    ) -> DiaryLink {
        let now = SyncableTimestamp.nowMilliseconds()
        return DiaryLink(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            diaryID: diaryID,
            sourceType: record.sourceType.rawValue,
            sourceID: record.sourceID,
            sourceTitle: record.title,
            sourceSubtitle: record.subtitle,
            sourceIcon: record.systemImage,
            sourceOccurredAt: record.occurredAt,
            snapshotJSON: record.snapshotJSON
        )
    }

    func refreshingSnapshot(from record: DiaryLinkedRecord) -> DiaryLink {
        var link = self
        link.sourceTitle = record.title
        link.sourceSubtitle = record.subtitle
        link.sourceIcon = record.systemImage
        link.sourceOccurredAt = record.occurredAt
        link.snapshotJSON = record.snapshotJSON
        return link
    }
}
