import Foundation
import GRDB

/// “选什么”的一次随机结果快照。
struct DecisionChoiceRecord: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "decision_choice_records"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var collectionID: String
    var collectionTitle: String
    var optionID: String
    var optionTitle: String
    var optionDetail: String?
    var optionCustomInfo: String?
    var optionImageData: Data?
    var selectedAt: Int64

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case collectionID = "collection_id"
        case collectionTitle = "collection_title"
        case optionID = "option_id"
        case optionTitle = "option_title"
        case optionDetail = "option_detail"
        case optionCustomInfo = "option_custom_info"
        case optionImageData = "option_image_data"
        case selectedAt = "selected_at"
    }

    static func makeNew(
        collectionID: String,
        collectionTitle: String,
        option: DecisionOption,
        deviceID: String
    ) -> DecisionChoiceRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        return DecisionChoiceRecord(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            collectionID: collectionID,
            collectionTitle: collectionTitle,
            optionID: option.id,
            optionTitle: option.title,
            optionDetail: option.detail,
            optionCustomInfo: option.customInfo,
            optionImageData: option.imageData,
            selectedAt: now
        )
    }
}
