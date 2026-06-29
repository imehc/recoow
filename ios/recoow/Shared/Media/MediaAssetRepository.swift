import CryptoKit
import Foundation
import GRDB
import UIKit

struct MediaAssetLibraryItem: Identifiable, Sendable {
    let asset: MediaAsset
    let referenceCount: Int

    var id: String { asset.id }
}

enum MediaAssetRepositoryError: LocalizedError {
    case assetStateChanged

    var errorDescription: String? {
        switch self {
        case .assetStateChanged:
            AppLocalization.string("素材状态已变化，请刷新后重试。")
        }
    }
}

final class MediaAssetRepository: @unchecked Sendable {
    private let database: AppDatabase
    private let changeLogRepository: ChangeLogRepository
    private let deviceIdentifier: DeviceIdentifier
    private let objectStore: MediaAssetObjectStore

    init(
        database: AppDatabase,
        changeLogRepository: ChangeLogRepository,
        deviceIdentifier: DeviceIdentifier,
        objectStore: MediaAssetObjectStore = .shared
    ) {
        self.database = database
        self.changeLogRepository = changeLogRepository
        self.deviceIdentifier = deviceIdentifier
        self.objectStore = objectStore
    }

    func fetchImageAssets() throws -> [MediaAsset] {
        try database.reader.read { db in
            try MediaAsset
                .filter(Column("kind") == "image")
                .filter(Column("deleted_at") == nil)
                .order(Column("sort_order").asc, Column("updated_at").desc, Column("created_at").desc)
                .fetchAll(db)
        }
    }

    func fetchImageAssetLibraryItems() throws -> [MediaAssetLibraryItem] {
        try database.reader.read { db in
            let assets = try MediaAsset
                .filter(Column("kind") == "image")
                .filter(Column("deleted_at") == nil)
                .order(Column("sort_order").asc, Column("updated_at").desc, Column("created_at").desc)
                .fetchAll(db)

            return try assets.map { asset in
                MediaAssetLibraryItem(
                    asset: asset,
                    referenceCount: try Self.referenceCount(db: db, assetID: asset.id)
                )
            }
        }
    }

    func createImageAsset(data: Data, mimeType: String = "image/jpeg") throws -> MediaAsset {
        let image = UIImage(data: data)
        let checksum = Self.sha256HexString(for: data)

        return try database.writer.write { db in
            if let existing = try MediaAsset
                .filter(Column("kind") == "image")
                .filter(Column("checksum") == checksum)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db) {
                // 同一张图只保留一份对象文件；旧备份恢复后如果文件缺失，会用 inline_data 自动补回。
                if objectStore.contains(storageKey: existing.storageKey) == false {
                    let inlineData = existing.inlineData ?? data
                    try objectStore.write(inlineData, storageKey: existing.storageKey)
                }
                return existing
            }

            let asset = MediaAsset.makeImage(
                data: data,
                mimeType: mimeType,
                width: image.map { Int($0.size.width) },
                height: image.map { Int($0.size.height) },
                checksum: checksum,
                sortOrder: try Self.nextImageAssetSortOrder(db: db),
                deviceID: deviceIdentifier.value,
                inlineData: nil
            )
            try objectStore.write(data, storageKey: asset.storageKey)
            try asset.insert(db)
            try appendChange(db: db, record: asset, operation: .insert)
            return asset
        }
    }

    func replaceImageAsset(id: String, data: Data, mimeType: String = "image/jpeg") throws -> MediaAsset {
        let image = UIImage(data: data)
        let checksum = Self.sha256HexString(for: data)

        return try database.writer.write { db in
            guard var asset = try MediaAsset
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db) else {
                throw DatabaseError(message: "Media asset not found: \(id)")
            }

            asset.updatedAt = SyncableTimestamp.nowMilliseconds()
            asset.syncStatus = .pending
            asset.mimeType = mimeType
            asset.byteCount = data.count
            asset.checksum = checksum
            asset.width = image.map { Int($0.size.width) }
            asset.height = image.map { Int($0.size.height) }
            asset.storageKey = MediaAssetObjectStore.storageKey(for: asset.id, mimeType: mimeType)
            asset.inlineData = nil

            try objectStore.write(data, storageKey: asset.storageKey)
            try asset.update(db)
            try appendChange(db: db, record: asset, operation: .update)
            return asset
        }
    }

    func deleteImageAsset(id: String) throws {
        try database.writer.write { db in
            let referenceCount = try Self.referenceCount(db: db, assetID: id)
            guard referenceCount == 0 else {
                throw MediaAssetRepositoryError.assetStateChanged
            }

            guard var asset = try MediaAsset
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db) else {
                return
            }

            let now = SyncableTimestamp.nowMilliseconds()
            asset.deletedAt = now
            asset.updatedAt = now
            asset.syncStatus = .pending

            try asset.update(db)
            try appendChange(db: db, record: asset, operation: .delete)
        }
    }

    func reorderImageAssets(ids orderedIDs: [String]) throws {
        guard orderedIDs.isEmpty == false else { return }

        try database.writer.write { db in
            guard Set(orderedIDs).count == orderedIDs.count else {
                throw MediaAssetRepositoryError.assetStateChanged
            }

            let assets = try MediaAsset
                .filter(orderedIDs.contains(Column("id")))
                .filter(Column("kind") == "image")
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)

            guard assets.count == orderedIDs.count else {
                throw MediaAssetRepositoryError.assetStateChanged
            }

            let assetsByID = Dictionary(uniqueKeysWithValues: assets.map { ($0.id, $0) })
            let now = SyncableTimestamp.nowMilliseconds()

            for (index, id) in orderedIDs.enumerated() {
                guard var asset = assetsByID[id] else {
                    throw MediaAssetRepositoryError.assetStateChanged
                }

                guard asset.sortOrder != index else { continue }

                asset.sortOrder = index
                asset.updatedAt = now
                asset.syncStatus = .pending

                try asset.update(db)
                try appendChange(db: db, record: asset, operation: .update)
            }
        }
    }

    func data(for asset: MediaAsset) -> Data? {
        if let data = objectStore.data(for: asset.storageKey) {
            return data
        }

        if let inlineData = asset.inlineData {
            try? objectStore.write(inlineData, storageKey: asset.storageKey)
            return inlineData
        }

        return nil
    }

    nonisolated static func prepareBackupCopy(at backupURL: URL) throws {
        let queue = try DatabaseQueue(path: backupURL.path(percentEncoded: false))
        defer {
            try? queue.close()
        }

        try queue.write { db in
            let objectStore = MediaAssetObjectStore.shared
            let assets = try MediaAsset
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            var missingAssetCount = 0

            for var asset in assets {
                guard let data = objectStore.data(for: asset.storageKey) else {
                    if asset.inlineData == nil {
                        missingAssetCount += 1
                    }
                    continue
                }

                guard asset.inlineData != data else {
                    continue
                }

                // inline_data 是备份承载层，不作为用户编辑变更写入 change_log。
                asset.inlineData = data
                try asset.update(db)
            }

            if missingAssetCount > 0 {
                throw AppDataTransferError.missingMediaAssets(missingAssetCount)
            }
        }
    }

    nonisolated static func restoreInlineAssetsToObjectStore(database: AppDatabase) throws {
        try database.writer.write { db in
            let objectStore = MediaAssetObjectStore.shared
            let assets = try MediaAsset
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)

            for var asset in assets {
                if objectStore.contains(storageKey: asset.storageKey) == false,
                   let data = asset.inlineData {
                    try objectStore.write(data, storageKey: asset.storageKey)
                }

                if asset.inlineData != nil {
                    // 导入完成后立刻清掉数据库里的图片二进制，运行时只保留对象文件和引用。
                    asset.inlineData = nil
                    try asset.update(db)
                }
            }
        }
    }

    private func appendChange(db: Database, record: MediaAsset, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: MediaAsset.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private static func nextImageAssetSortOrder(db: Database) throws -> Int {
        let minimumSortOrder = try Int.fetchOne(
            db,
            sql: """
                SELECT MIN(sort_order)
                FROM media_assets
                WHERE kind = 'image'
                  AND deleted_at IS NULL
                """
        ) ?? 1

        return minimumSortOrder - 1
    }

    private static func referenceCount(db: Database, assetID: String) throws -> Int {
        var count = 0

        for reference in imageAssetReferenceColumns {
            guard try db.tableExists(reference.table),
                  try db.columns(in: reference.table).contains(where: { $0.name == reference.column }) else {
                continue
            }

            count += try Int.fetchOne(
                db,
                sql: """
                    SELECT COUNT(*)
                    FROM \(reference.table)
                    WHERE \(reference.column) = ?
                      AND deleted_at IS NULL
                    """,
                arguments: [assetID]
            ) ?? 0
        }

        return count
    }

    private static var imageAssetReferenceColumns: [(table: String, column: String)] {
        [
            (MediaAttachment.databaseTableName, "asset_id"),
            (ReminderRecord.databaseTableName, "image_asset_id"),
            (BillRecord.databaseTableName, "image_asset_id"),
            (StoredItem.databaseTableName, "image_asset_id"),
            (DecisionOption.databaseTableName, "image_asset_id"),
            (DecisionChoiceRecord.databaseTableName, "option_image_asset_id")
        ]
    }

    private static func sha256HexString(for data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
