import Foundation
import GRDB

struct FoodEntry: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "food_entries"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var mealKind: String
    var portion: String?
    var note: String?
    var billID: String?
    var occurredAt: Int64

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case title
        case mealKind = "meal_kind"
        case portion
        case note
        case billID = "bill_id"
        case occurredAt = "occurred_at"
    }

    nonisolated var occurredDate: Date {
        Date(timeIntervalSince1970: Double(occurredAt) / 1000)
    }

    nonisolated var foodMealKind: FoodMealKind {
        FoodMealKind(rawValue: mealKind) ?? .other
    }

    nonisolated var normalizedPortion: String? {
        normalizedText(portion)
    }

    nonisolated var normalizedNote: String? {
        normalizedText(note)
    }

    nonisolated var subtitleText: String? {
        [normalizedPortion, normalizedNote]
            .compactMap(\.self)
            .joined(separator: " · ")
            .nilIfEmpty
    }

    static func makeNew(
        title: String,
        mealKind: FoodMealKind,
        portion: String?,
        note: String?,
        billID: String?,
        occurredAt: Int64,
        deviceID: String
    ) -> FoodEntry {
        let now = SyncableTimestamp.nowMilliseconds()
        return FoodEntry(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            mealKind: mealKind.rawValue,
            portion: portion,
            note: note,
            billID: billID,
            occurredAt: occurredAt
        )
    }

    nonisolated private func normalizedText(_ value: String?) -> String? {
        let trimmedValue = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmedValue.isEmpty ? nil : trimmedValue
    }
}

private extension String {
    nonisolated var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
