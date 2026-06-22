import Foundation
import GRDB

struct DiaryTagReference: Identifiable, Codable, Hashable, Sendable {
    var key: String
    var value: String

    var id: String { key }
}

struct DiaryEntry: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "diary_entries"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var content: String
    var mood: String
    var tagsJSON: String
    var occurredAt: Int64
    var latitude: Double?
    var longitude: Double?
    var horizontalAccuracy: Double?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case title
        case content
        case mood
        case tagsJSON = "tags_json"
        case occurredAt = "occurred_at"
        case latitude
        case longitude
        case horizontalAccuracy = "horizontal_accuracy"
    }

    var occurredDate: Date {
        Date(timeIntervalSince1970: Double(occurredAt) / 1000)
    }

    var diaryMood: DiaryMood {
        DiaryMood(rawValue: mood) ?? .calm
    }

    var tags: [String] {
        tagReferences.map(\.value)
    }

    var tagReferences: [DiaryTagReference] {
        Self.decodeTags(from: tagsJSON)
    }

    var hasLocation: Bool {
        latitude != nil && longitude != nil
    }

    var locationText: String? {
        guard let latitude, let longitude else { return nil }
        return AppFormatters.coordinate(latitude: latitude, longitude: longitude)
    }

    var previewText: String {
        let normalized = content.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized.isEmpty ? AppLocalization.string("未填写正文") : normalized
    }

    static func makeNew(
        title: String,
        content: String,
        mood: DiaryMood,
        tags: [DiaryTagReference],
        occurredAt: Int64,
        latitude: Double? = nil,
        longitude: Double? = nil,
        horizontalAccuracy: Double? = nil,
        deviceID: String
    ) -> DiaryEntry {
        let now = SyncableTimestamp.nowMilliseconds()
        return DiaryEntry(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            content: content,
            mood: mood.rawValue,
            tagsJSON: encodeTags(tags),
            occurredAt: occurredAt,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy
        )
    }

    static func encodeTags(_ tags: [DiaryTagReference]) -> String {
        let normalized = tags
            .map {
                DiaryTagReference(
                    key: $0.key.trimmingCharacters(in: .whitespacesAndNewlines),
                    value: $0.value.trimmingCharacters(in: .whitespacesAndNewlines)
                )
            }
            .filter { $0.key.isEmpty == false && $0.value.isEmpty == false }

        guard let data = try? JSONEncoder().encode(normalized) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }

    private static func decodeTags(from json: String) -> [DiaryTagReference] {
        guard let data = json.data(using: .utf8),
              data.isEmpty == false else {
            return []
        }

        return (try? JSONDecoder().decode([DiaryTagReference].self, from: data)) ?? []
    }
}
