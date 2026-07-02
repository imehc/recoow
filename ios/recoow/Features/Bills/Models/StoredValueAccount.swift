import Foundation
import GRDB

struct StoredValueAccount: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "stored_value_accounts"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var name: String
    var kind: String
    var balanceCents: Int64
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
        case kind
        case balanceCents = "balance_cents"
        case note
    }

    var accountKind: StoredValueAccountKind {
        StoredValueAccountKind(rawValue: kind) ?? .other
    }

    static func makeNew(
        name: String,
        kind: StoredValueAccountKind,
        balanceCents: Int64,
        note: String? = nil,
        deviceID: String
    ) -> StoredValueAccount {
        let now = SyncableTimestamp.nowMilliseconds()
        return StoredValueAccount(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            name: name,
            kind: kind.rawValue,
            balanceCents: balanceCents,
            note: note
        )
    }
}
