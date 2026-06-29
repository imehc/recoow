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
    var billIDsJSON: String
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
        case billIDsJSON = "bill_ids_json"
        case occurredAt = "occurred_at"
    }

    init(
        id: String,
        createdAt: Int64,
        updatedAt: Int64,
        deletedAt: Int64?,
        syncStatus: SyncStatus,
        deviceID: String,
        serverVersion: Int64?,
        title: String,
        mealKind: String,
        portion: String?,
        note: String?,
        billID: String?,
        billIDsJSON: String,
        occurredAt: Int64
    ) {
        self.id = id
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
        self.syncStatus = syncStatus
        self.deviceID = deviceID
        self.serverVersion = serverVersion
        self.title = title
        self.mealKind = mealKind
        self.portion = portion
        self.note = note
        self.billID = billID
        self.billIDsJSON = billIDsJSON
        self.occurredAt = occurredAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        createdAt = try container.decode(Int64.self, forKey: .createdAt)
        updatedAt = try container.decode(Int64.self, forKey: .updatedAt)
        deletedAt = try container.decodeIfPresent(Int64.self, forKey: .deletedAt)
        syncStatus = try container.decode(SyncStatus.self, forKey: .syncStatus)
        deviceID = try container.decode(String.self, forKey: .deviceID)
        serverVersion = try container.decodeIfPresent(Int64.self, forKey: .serverVersion)
        title = try container.decode(String.self, forKey: .title)
        mealKind = try container.decode(String.self, forKey: .mealKind)
        portion = try container.decodeIfPresent(String.self, forKey: .portion)
        note = try container.decodeIfPresent(String.self, forKey: .note)
        billID = try container.decodeIfPresent(String.self, forKey: .billID)
        billIDsJSON = try container.decodeIfPresent(String.self, forKey: .billIDsJSON)
            ?? Self.encodeBillIDs(billID.map { [$0] } ?? [])
        occurredAt = try container.decode(Int64.self, forKey: .occurredAt)
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

    nonisolated var billIDs: [String] {
        get {
            var seenIDs = Set<String>()
            var ids = Self.decodeBillIDs(from: billIDsJSON)

            if let billID {
                ids.insert(billID, at: 0)
            }

            return ids.filter { id in
                seenIDs.insert(id).inserted
            }
        }
        set {
            let normalizedIDs = Self.normalizedBillIDs(newValue)
            billID = normalizedIDs.first
            billIDsJSON = Self.encodeBillIDs(normalizedIDs)
        }
    }

    nonisolated var linkedBillCount: Int {
        billIDs.count
    }

    nonisolated var hasLinkedBills: Bool {
        billIDs.isEmpty == false
    }

    static func makeNew(
        title: String,
        mealKind: FoodMealKind,
        portion: String?,
        note: String?,
        billIDs: [String],
        occurredAt: Int64,
        deviceID: String
    ) -> FoodEntry {
        let now = SyncableTimestamp.nowMilliseconds()
        let normalizedBillIDs = normalizedBillIDs(billIDs)
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
            billID: normalizedBillIDs.first,
            billIDsJSON: encodeBillIDs(normalizedBillIDs),
            occurredAt: occurredAt
        )
    }

    nonisolated static func encodeBillIDs(_ ids: [String]) -> String {
        let normalizedIDs = normalizedBillIDs(ids)
        guard let data = try? JSONEncoder().encode(normalizedIDs) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }

    nonisolated private static func decodeBillIDs(from json: String) -> [String] {
        guard let data = json.data(using: .utf8),
              data.isEmpty == false else {
            return []
        }

        return normalizedBillIDs((try? JSONDecoder().decode([String].self, from: data)) ?? [])
    }

    nonisolated private static func normalizedBillIDs(_ ids: [String]) -> [String] {
        var seenIDs = Set<String>()
        return ids
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.isEmpty == false }
            .filter { id in
                seenIDs.insert(id).inserted
            }
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
