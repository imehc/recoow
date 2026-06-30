import Foundation
import GRDB

struct SoftDeletedDataCleanupResult: Sendable {
    let deletedRowCount: Int
    let retainedRowCount: Int
}

struct AppStorageUsageSnapshot: Sendable {
    let databaseBytes: Int64
    let mediaObjectBytes: Int64
    let rollbackBackupBytes: Int64
    let cacheBytes: Int64
    let changeLogPayloadBytes: Int64
    let mediaAttachmentDataBytes: Int64
    let mediaAssetInlineDataBytes: Int64
    let legacyImageDataBytes: Int64

    var totalBytes: Int64 {
        databaseBytes + mediaObjectBytes + rollbackBackupBytes + cacheBytes
    }
}

struct StorageOptimizationResult: Sendable {
    let beforeUsage: AppStorageUsageSnapshot
    let afterUsage: AppStorageUsageSnapshot
    let deletedRowCount: Int
    let retainedRowCount: Int
    let sanitizedChangeLogRowCount: Int
    let restoredInlineAssetCount: Int
    let prunedMediaObjectCount: Int
    let removedRollbackBackupCount: Int

    var reclaimedBytes: Int64 {
        max(0, beforeUsage.totalBytes - afterUsage.totalBytes)
    }
}

extension AppDatabase {
    nonisolated func clearSoftDeletedRecords() throws -> SoftDeletedDataCleanupResult {
        try writer.write { db in
            var totalSoftDeletedRows = 0
            for statement in SoftDeletedCleanupStatement.all {
                totalSoftDeletedRows += try countRows(db: db, table: statement.table, condition: "deleted_at IS NOT NULL")
            }
            var deletedRowCount = 0

            for statement in SoftDeletedCleanupStatement.all {
                deletedRowCount += try countRows(db: db, table: statement.table, condition: statement.condition)
                try db.execute(sql: "DELETE FROM \(statement.table) WHERE \(statement.condition)")
            }

            return SoftDeletedDataCleanupResult(
                deletedRowCount: deletedRowCount,
                retainedRowCount: max(0, totalSoftDeletedRows - deletedRowCount)
            )
        }
    }

    nonisolated func storageUsageSnapshot() throws -> AppStorageUsageSnapshot {
        let databaseBreakdown = try reader.read { db in
            try storageBreakdown(db: db)
        }
        try checkpointDatabaseFilesForSizeMeasurement()

        let databaseBytes = try databaseFilesByteCount()
        let mediaObjectBytes = try MediaAssetObjectStore.shared.totalByteCount()
        let rollbackBackupBytes = try directoryByteCount(at: dataBackupsDirectoryURL(create: false))
        let cacheBytes = try directoryByteCount(at: cachesDirectoryURL())

        return AppStorageUsageSnapshot(
            databaseBytes: databaseBytes,
            mediaObjectBytes: mediaObjectBytes,
            rollbackBackupBytes: rollbackBackupBytes,
            cacheBytes: cacheBytes,
            changeLogPayloadBytes: databaseBreakdown.changeLogPayloadBytes,
            mediaAttachmentDataBytes: databaseBreakdown.mediaAttachmentDataBytes,
            mediaAssetInlineDataBytes: databaseBreakdown.mediaAssetInlineDataBytes,
            legacyImageDataBytes: databaseBreakdown.legacyImageDataBytes
        )
    }

    nonisolated func optimizeStorage() throws -> StorageOptimizationResult {
        let beforeUsage = try storageUsageSnapshot()
        let cleanupResult = try clearSoftDeletedRecords()
        let sanitizedChangeLogRowCount = try sanitizeChangeLogPayloads()
        let restoredInlineAssetCount = try restoreInlineMediaAssetsToObjectStore()
        let retainedStorageKeys = try activeMediaAssetStorageKeys()
        let prunedMediaObjectCount = try MediaAssetObjectStore.shared.removeObjects(excluding: retainedStorageKeys)
        let removedRollbackBackupCount = try removeRollbackBackups(keepingNewest: 1)

        try compactDatabaseFiles()

        return StorageOptimizationResult(
            beforeUsage: beforeUsage,
            afterUsage: try storageUsageSnapshot(),
            deletedRowCount: cleanupResult.deletedRowCount,
            retainedRowCount: cleanupResult.retainedRowCount,
            sanitizedChangeLogRowCount: sanitizedChangeLogRowCount,
            restoredInlineAssetCount: restoredInlineAssetCount,
            prunedMediaObjectCount: prunedMediaObjectCount,
            removedRollbackBackupCount: removedRollbackBackupCount
        )
    }

    nonisolated func settleDatabaseFilesAfterImport() throws {
        try compactDatabaseFiles()
    }

    nonisolated private func countRows(db: Database, table: String, condition: String) throws -> Int {
        try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM \(table) WHERE \(condition)") ?? 0
    }

    nonisolated private func sanitizeChangeLogPayloads() throws -> Int {
        try writer.write { db in
            guard try db.tableExists("change_log") else { return 0 }

            let rows = try Row.fetchAll(
                db,
                sql: """
                    SELECT id, entity_table, payload_json
                    FROM change_log
                    """
            )
            var sanitizedCount = 0

            for row in rows {
                let id: Int64 = row["id"]
                let table: String = row["entity_table"]
                let payloadJSON: String = row["payload_json"]
                guard let sanitizedJSON = try ChangeLogRepository.sanitizedPayloadJSON(payloadJSON, table: table),
                      sanitizedJSON != payloadJSON else {
                    continue
                }

                try db.execute(
                    sql: "UPDATE change_log SET payload_json = ? WHERE id = ?",
                    arguments: [sanitizedJSON, id]
                )
                sanitizedCount += 1
            }

            return sanitizedCount
        }
    }

    nonisolated private func restoreInlineMediaAssetsToObjectStore() throws -> Int {
        try writer.write { db in
            guard try db.tableExists("media_assets"),
                  try db.columns(in: "media_assets").contains(where: { $0.name == "inline_data" }),
                  try db.columns(in: "media_assets").contains(where: { $0.name == "storage_key" }) else {
                return 0
            }

            let rows = try Row.fetchAll(
                db,
                sql: """
                    SELECT id, storage_key, inline_data
                    FROM media_assets
                    WHERE inline_data IS NOT NULL
                      AND deleted_at IS NULL
                    """
            )
            var restoredCount = 0

            for row in rows {
                let id: String = row["id"]
                let storageKey: String = row["storage_key"]
                let data: Data = row["inline_data"]

                try MediaAssetObjectStore.shared.write(data, storageKey: storageKey)
                try db.execute(
                    sql: "UPDATE media_assets SET inline_data = NULL WHERE id = ?",
                    arguments: [id]
                )
                restoredCount += 1
            }

            return restoredCount
        }
    }

    nonisolated private func activeMediaAssetStorageKeys() throws -> Set<String> {
        try reader.read { db in
            guard try db.tableExists("media_assets"),
                  try db.columns(in: "media_assets").contains(where: { $0.name == "storage_key" }) else {
                return []
            }

            let keys = try String.fetchAll(
                db,
                sql: """
                    SELECT storage_key
                    FROM media_assets
                    WHERE deleted_at IS NULL
                    """
            )
            return Set(keys)
        }
    }

    nonisolated private func compactDatabaseFiles() throws {
        guard databaseURL != nil else { return }

        try dbQueue.writeWithoutTransaction { db in
            try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
            try db.execute(sql: "VACUUM")
            try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
        }
    }

    nonisolated private func checkpointDatabaseFilesForSizeMeasurement() throws {
        guard databaseURL != nil else { return }

        try dbQueue.writeWithoutTransaction { db in
            try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
        }
    }

    nonisolated private func storageBreakdown(db: Database) throws -> DatabaseStorageBreakdown {
        let legacyImageDataBytes = try Self.legacyImageBlobColumns.reduce(Int64(0)) { total, reference in
            let bytes = try sumLength(db: db, table: reference.table, column: reference.column)
            return total + bytes
        }

        return DatabaseStorageBreakdown(
            changeLogPayloadBytes: try sumLength(db: db, table: "change_log", column: "payload_json"),
            mediaAttachmentDataBytes: try sumLength(db: db, table: "media_attachments", column: "data"),
            mediaAssetInlineDataBytes: try sumLength(db: db, table: "media_assets", column: "inline_data"),
            legacyImageDataBytes: legacyImageDataBytes
        )
    }

    nonisolated private func sumLength(db: Database, table: String, column: String) throws -> Int64 {
        guard try db.tableExists(table),
              try db.columns(in: table).contains(where: { $0.name == column }) else {
            return 0
        }

        return try Int64.fetchOne(
            db,
            sql: "SELECT COALESCE(SUM(LENGTH(\(column))), 0) FROM \(table)"
        ) ?? 0
    }

    nonisolated private func databaseFilesByteCount() throws -> Int64 {
        guard let databaseURL else { return 0 }

        let databasePath = databaseURL.path(percentEncoded: false)
        let urls = [
            databaseURL,
            URL(fileURLWithPath: "\(databasePath)-wal"),
            URL(fileURLWithPath: "\(databasePath)-shm")
        ]

        return try urls.reduce(Int64(0)) { total, url in
            try total + fileByteCount(at: url)
        }
    }

    nonisolated private func removeRollbackBackups(keepingNewest retainedBackupCount: Int) throws -> Int {
        let directoryURL = try dataBackupsDirectoryURL(create: false)
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: directoryURL.path(percentEncoded: false)) else {
            return 0
        }

        let backupURLs = try fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey]
        )
        .filter { url in
            guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey]) else {
                return false
            }

            return values.isRegularFile == true && url.pathExtension == "recoowbackup"
        }
        .sorted { lhs, rhs in
            modificationDate(for: lhs) > modificationDate(for: rhs)
        }

        var removedCount = 0
        for url in backupURLs.dropFirst(max(0, retainedBackupCount)) {
            try fileManager.removeItem(at: url)
            removedCount += 1
        }

        return removedCount
    }

    nonisolated private func dataBackupsDirectoryURL(create: Bool) throws -> URL {
        let appSupport = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directoryURL = appSupport.appending(path: "DataBackups", directoryHint: .isDirectory)
        if create {
            try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        }

        return directoryURL
    }

    nonisolated private func cachesDirectoryURL() throws -> URL {
        try FileManager.default.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
    }

    nonisolated private func directoryByteCount(at directoryURL: URL) throws -> Int64 {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: directoryURL.path(percentEncoded: false)) else {
            return 0
        }

        let fileURLs = fileManager.enumerator(
            at: directoryURL,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )?.compactMap { $0 as? URL } ?? []

        return try fileURLs.reduce(Int64(0)) { total, url in
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            guard values.isRegularFile == true else { return total }
            return total + Int64(values.fileSize ?? 0)
        }
    }

    nonisolated private func fileByteCount(at url: URL) throws -> Int64 {
        let path = url.path(percentEncoded: false)
        guard FileManager.default.fileExists(atPath: path) else {
            return 0
        }

        let attributes = try FileManager.default.attributesOfItem(atPath: path)
        return (attributes[.size] as? NSNumber)?.int64Value ?? 0
    }

    nonisolated private func modificationDate(for url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }

    nonisolated private static let legacyImageBlobColumns: [(table: String, column: String)] = [
        ("bills", "image_data"),
        ("reminders", "image_data"),
        ("stored_items", "image_data"),
        ("decision_options", "image_data"),
        ("decision_choice_records", "option_image_data")
    ]
}

private struct DatabaseStorageBreakdown {
    let changeLogPayloadBytes: Int64
    let mediaAttachmentDataBytes: Int64
    let mediaAssetInlineDataBytes: Int64
    let legacyImageDataBytes: Int64
}

private struct SoftDeletedCleanupStatement {
    let table: String
    let condition: String

    nonisolated static let all: [SoftDeletedCleanupStatement] = [
        .init(table: "media_attachments", condition: "deleted_at IS NOT NULL"),
        .init(table: "diary_links", condition: "deleted_at IS NOT NULL"),
        .init(table: "track_points", condition: "deleted_at IS NOT NULL"),
        .init(table: "track_segments", condition: "deleted_at IS NOT NULL"),
        .init(table: "decision_options", condition: "deleted_at IS NOT NULL"),
        .init(table: "stored_items", condition: "deleted_at IS NOT NULL"),
        .init(
            table: "food_entries",
            condition: """
            deleted_at IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM media_attachments
                WHERE media_attachments.owner_type = 'foodEntry'
                  AND media_attachments.owner_id = food_entries.id
                  AND media_attachments.deleted_at IS NULL
            )
            """
        ),
        .init(table: "decision_choice_records", condition: "deleted_at IS NOT NULL"),
        .init(table: "reminders", condition: "deleted_at IS NOT NULL"),
        .init(table: "anniversaries", condition: "deleted_at IS NOT NULL"),
        .init(table: "food_day_records", condition: "deleted_at IS NOT NULL"),
        .init(table: "diary_tags", condition: "deleted_at IS NOT NULL"),
        .init(
            table: "diary_entries",
            condition: """
            deleted_at IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM diary_links
                WHERE diary_links.diary_id = diary_entries.id
                  AND diary_links.deleted_at IS NULL
            )
            AND NOT EXISTS (
                SELECT 1
                FROM media_attachments
                WHERE media_attachments.owner_type = 'diary'
                  AND media_attachments.owner_id = diary_entries.id
                  AND media_attachments.deleted_at IS NULL
            )
            """
        ),
        .init(
            table: "decision_collections",
            condition: """
            deleted_at IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM decision_options
                WHERE decision_options.collection_id = decision_collections.id
                  AND decision_options.deleted_at IS NULL
            )
            """
        ),
        .init(
            table: "item_categories",
            condition: """
            deleted_at IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM stored_items
                WHERE stored_items.category_id = item_categories.id
                  AND stored_items.deleted_at IS NULL
            )
            """
        ),
        .init(
            table: "bills",
            condition: """
            deleted_at IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM food_entries
                WHERE (
                    food_entries.bill_id = bills.id
                    OR EXISTS (
                        SELECT 1
                        FROM json_each(food_entries.bill_ids_json)
                        WHERE value = bills.id
                    )
                )
                  AND food_entries.deleted_at IS NULL
            )
            """
        ),
        .init(
            table: "tracks",
            condition: """
            deleted_at IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM track_points
                WHERE track_points.track_id = tracks.id
                  AND track_points.deleted_at IS NULL
            )
            AND NOT EXISTS (
                SELECT 1
                FROM track_segments
                WHERE track_segments.track_id = tracks.id
                  AND track_segments.deleted_at IS NULL
            )
            """
        )
    ]
}
