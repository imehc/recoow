import Foundation

struct AppDataTransferMetadata: Sendable {
    nonisolated static let currentFormatVersion = 1

    let formatVersion: Int
    let databaseSchemaVersion: Int
    let appVersion: String
    let appBuild: String
    let exportedAt: Date
    let sourceDeviceID: String
    let preferences: AppDataTransferPreferences

    // App 版本用于追踪备份来源；真正决定能否导入的是格式版本和数据库 schema 版本。
    nonisolated static func current(
        sourceDeviceID: String,
        preferences: AppDataTransferPreferences,
        bundle: Bundle = .main
    ) -> AppDataTransferMetadata {
        AppDataTransferMetadata(
            formatVersion: currentFormatVersion,
            databaseSchemaVersion: AppMigrator.currentSchemaVersion,
            appVersion: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
            appBuild: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown",
            exportedAt: Date(),
            sourceDeviceID: sourceDeviceID,
            preferences: preferences
        )
    }
}

struct AppDataTransferPreferences: Sendable {
    let languageRawValue: String?
    let appearanceRawValue: String?
    let hiddenToolRouteIDs: [String]
}

extension AppDataTransferMetadata {
    nonisolated func encodedJSONString() throws -> String {
        let preferencesJSON: [String: Any] = [
            "languageRawValue": preferences.languageRawValue ?? NSNull(),
            "appearanceRawValue": preferences.appearanceRawValue ?? NSNull(),
            "hiddenToolRouteIDs": preferences.hiddenToolRouteIDs
        ]
        let json: [String: Any] = [
            "formatVersion": formatVersion,
            "databaseSchemaVersion": databaseSchemaVersion,
            "appVersion": appVersion,
            "appBuild": appBuild,
            "exportedAt": Self.iso8601Formatter.string(from: exportedAt),
            "sourceDeviceID": sourceDeviceID,
            "preferences": preferencesJSON
        ]
        let data = try JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])
        guard let string = String(data: data, encoding: .utf8) else {
            throw AppDataTransferError.unreadableBackup
        }

        return string
    }

    nonisolated static func decoded(fromJSONString jsonString: String) throws -> AppDataTransferMetadata {
        guard let data = jsonString.data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let formatVersion = json["formatVersion"] as? Int,
              let databaseSchemaVersion = json["databaseSchemaVersion"] as? Int,
              let appVersion = json["appVersion"] as? String,
              let appBuild = json["appBuild"] as? String,
              let exportedAtString = json["exportedAt"] as? String,
              let exportedAt = iso8601Formatter.date(from: exportedAtString),
              let sourceDeviceID = json["sourceDeviceID"] as? String,
              let preferencesJSON = json["preferences"] as? [String: Any],
              let hiddenToolRouteIDs = preferencesJSON["hiddenToolRouteIDs"] as? [String]
        else {
            throw AppDataTransferError.missingMetadata
        }

        return AppDataTransferMetadata(
            formatVersion: formatVersion,
            databaseSchemaVersion: databaseSchemaVersion,
            appVersion: appVersion,
            appBuild: appBuild,
            exportedAt: exportedAt,
            sourceDeviceID: sourceDeviceID,
            preferences: AppDataTransferPreferences(
                languageRawValue: preferencesJSON["languageRawValue"] as? String,
                appearanceRawValue: preferencesJSON["appearanceRawValue"] as? String,
                hiddenToolRouteIDs: hiddenToolRouteIDs
            )
        )
    }

    nonisolated private static var iso8601Formatter: ISO8601DateFormatter {
        ISO8601DateFormatter()
    }
}

struct AppDataImportPreview: Sendable {
    let metadata: AppDataTransferMetadata
    let fileName: String

    var appVersionDisplay: String {
        "\(metadata.appVersion) (\(metadata.appBuild))"
    }
}

struct AppDataImportResult: Sendable {
    let metadata: AppDataTransferMetadata
    let rollbackBackupURL: URL
    let importedRowCount: Int
}

enum AppDataImportMode: Hashable, Sendable {
    case mergeMissing
    case replaceAll

    nonisolated var title: String {
        switch self {
        case .mergeMissing:
            AppLocalization.string("增量导入")
        case .replaceAll:
            AppLocalization.string("覆盖全部")
        }
    }
}

enum AppDataImportScope: String, CaseIterable, Identifiable, Hashable, Sendable {
    case decisionMaker
    case itemLocator
    case locationTracker
    case reminders
    case bills
    case foodJournal
    case diary
    case anniversaries

    nonisolated var id: String { rawValue }

    nonisolated var title: String {
        switch self {
        case .decisionMaker:
            AppLocalization.string("选什么")
        case .itemLocator:
            AppLocalization.string("在哪里")
        case .locationTracker:
            AppLocalization.string("轨迹记录")
        case .reminders:
            AppLocalization.string("打卡任务")
        case .bills:
            AppLocalization.string("记一笔")
        case .foodJournal:
            AppLocalization.string("饮食记录")
        case .diary:
            AppLocalization.string("日记")
        case .anniversaries:
            AppLocalization.string("纪念日")
        }
    }

    nonisolated var tableNames: [String] {
        switch self {
        case .decisionMaker:
            ["decision_collections", "decision_options", "decision_choice_records"]
        case .itemLocator:
            ["item_categories", "stored_items"]
        case .locationTracker:
            ["tracks", "track_points", "track_segments"]
        case .reminders:
            ["reminders"]
        case .bills:
            ["bills"]
        case .foodJournal:
            ["food_entries", "food_day_records"]
        case .diary:
            ["diary_entries", "diary_tags", "diary_links"]
        case .anniversaries:
            ["anniversaries"]
        }
    }

    nonisolated var mediaOwnerTypes: [String] {
        switch self {
        case .foodJournal:
            [MediaAttachmentOwnerType.foodEntry.rawValue]
        case .diary:
            [MediaAttachmentOwnerType.diary.rawValue]
        default:
            []
        }
    }
}
