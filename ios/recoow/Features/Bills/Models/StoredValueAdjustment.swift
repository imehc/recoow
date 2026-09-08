import Foundation
import GRDB

nonisolated struct StoredValueAdjustment: Codable, FetchableRecord, PersistableRecord, SyncableRecord, Sendable {
    static let databaseTableName = "stored_value_adjustments"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?
    var accountID: String
    var amountCents: Int64
    var reason: String

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case accountID = "account_id"
        case amountCents = "amount_cents"
        case reason
    }
}
