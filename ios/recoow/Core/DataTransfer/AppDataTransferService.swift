import Foundation
import GRDB

final class AppDataTransferService: @unchecked Sendable {
    nonisolated private static let metadataTableName = "_recoow_backup_metadata"
    nonisolated private static let metadataKey = "metadata"
    nonisolated private static let incomingSchemaName = "incoming"
    nonisolated private static let changeLogTableName = "change_log"

    nonisolated private static let mergeTableOrder = [
        "decision_collections",
        "decision_options",
        "item_categories",
        "stored_items",
        "tracks",
        "track_points",
        "track_segments",
        "decision_choice_records",
        "reminders",
        "bills",
        "food_entries",
        "food_day_records",
        "anniversaries",
        "diary_entries",
        "diary_tags",
        "diary_links",
        "media_assets",
        "media_attachments"
    ]

    private let database: AppDatabase

    nonisolated init(database: AppDatabase) {
        self.database = database
    }

    nonisolated func exportBackup(
        sourceDeviceID: String,
        preferences: AppDataTransferPreferences
    ) throws -> URL {
        let exportURL = try makeTemporaryBackupURL(prefix: "recoow-export")
        try database.backup(to: exportURL)
        try MediaAssetRepository.prepareBackupCopy(at: exportURL)

        // 备份文件本质是 SQLite 快照；元数据单独写入同一个库，保证未来格式升级时可以先校验再恢复。
        let metadata = AppDataTransferMetadata.current(
            sourceDeviceID: sourceDeviceID,
            preferences: preferences
        )
        try writeMetadata(metadata, to: exportURL)
        try validateBackup(at: exportURL)
        return exportURL
    }

    nonisolated func previewImport(from sourceURL: URL) throws -> AppDataImportPreview {
        let localURL = try copyToTemporaryLocation(sourceURL)
        defer {
            try? FileManager.default.removeItem(at: localURL)
        }

        let metadata = try validateBackup(at: localURL)
        return AppDataImportPreview(metadata: metadata, fileName: sourceURL.lastPathComponent)
    }

    nonisolated func importBackup(
        from sourceURL: URL,
        mode: AppDataImportMode,
        scopes: Set<AppDataImportScope>
    ) throws -> AppDataImportResult {
        let localURL = try copyToTemporaryLocation(sourceURL)
        defer {
            try? FileManager.default.removeItem(at: localURL)
        }

        let metadata = try validateBackup(at: localURL)
        try migrateBackupCopy(at: localURL)
        let rollbackURL = try makePersistentRollbackURL()
        // 导入会覆盖当前数据库，因此先生成持久化回滚备份；失败时会自动恢复，成功后也保留给用户手动兜底。
        try database.backup(to: rollbackURL)

        do {
            let importedRowCount: Int
            switch mode {
            case .mergeMissing:
                importedRowCount = try mergeMissingRows(from: localURL, scopes: scopes)
            case .replaceAll:
                try database.restore(from: localURL)
                // 老版本备份允许导入，恢复后立即跑当前 App 的迁移，避免旧 schema 留在运行态。
                try database.migrate()
                try normalizeReplacedDatabaseSyncState()
                importedRowCount = 0
            }

            try MediaAssetRepository.restoreInlineAssetsToObjectStore(database: database)
            // 备份用 inline_data 承载媒体，导入后清空这些列需要压实库文件，设置页才会显示真实占用。
            try database.settleDatabaseFilesAfterImport()
            try database.verifyIntegrity()
            return AppDataImportResult(
                metadata: metadata,
                rollbackBackupURL: rollbackURL,
                importedRowCount: importedRowCount
            )
        } catch {
            let importError = error
            do {
                try database.restore(from: rollbackURL)
                try database.migrate()
                try database.verifyIntegrity()
            } catch {
                throw AppDataTransferError.rollbackFailed(
                    importError: importError.localizedDescription,
                    rollbackError: error.localizedDescription
                )
            }

            throw importError
        }
    }

    nonisolated private func migrateBackupCopy(at url: URL) throws {
        let queue = try DatabaseQueue(path: url.path(percentEncoded: false))
        defer {
            try? queue.close()
        }

        try AppMigrator.migrate(queue)
    }

    nonisolated private func normalizeReplacedDatabaseSyncState() throws {
        try database.writer.write { db in
            if try db.tableExists(Self.changeLogTableName) {
                try db.execute(sql: "DELETE FROM \(Self.quotedIdentifier(Self.changeLogTableName))")
            }

            for tableName in Self.syncableTableNames where try db.tableExists(tableName) {
                let columns = try columnNames(db: db, tableName: tableName, schemaName: nil)
                guard columns.contains("sync_status") else { continue }

                try db.execute(sql: """
                    UPDATE \(Self.quotedIdentifier(tableName))
                    SET \(Self.quotedIdentifier("sync_status")) = ?
                    """, arguments: [SyncStatus.synced.rawValue])
            }
        }
    }

    nonisolated private func mergeMissingRows(from sourceURL: URL, scopes: Set<AppDataImportScope>) throws -> Int {
        try database.writer.write { db in
            let sourcePath = sourceURL.path(percentEncoded: false)
            var importedRowCount = 0

            try db.execute(
                sql: "ATTACH DATABASE ? AS \(Self.incomingSchemaName)",
                arguments: [sourcePath]
            )
            defer {
                try? db.execute(sql: "DETACH DATABASE \(Self.incomingSchemaName)")
            }

            let selectedTableNames = Set(scopes.flatMap { $0.tableNames })
            for tableName in Self.mergeTableOrder where selectedTableNames.contains(tableName) {
                guard tableName != "media_assets", tableName != "media_attachments" else {
                    continue
                }

                guard try db.tableExists(tableName),
                      try db.tableExists(tableName, in: Self.incomingSchemaName)
                else {
                    continue
                }

                let columns = try commonColumns(db: db, tableName: tableName)
                guard columns.contains("id") else { continue }

                importedRowCount += try mergeMissingRows(
                    db: db,
                    tableName: tableName,
                    columns: columns,
                    whereClause: try businessTableImportWhereClause(db: db, tableName: tableName, columns: columns)
                )
            }

            if try db.tableExists("media_assets"),
               try db.tableExists("media_assets", in: Self.incomingSchemaName),
               let whereClause = try mediaAssetImportWhereClause(db: db, scopes: scopes) {
                let columns = try commonColumns(db: db, tableName: "media_assets")
                if columns.contains("id") {
                    importedRowCount += try mergeMissingRows(
                        db: db,
                        tableName: "media_assets",
                        columns: columns,
                        whereClause: whereClause
                    )
                }
            }

            let mediaOwnerTypes = Set(scopes.flatMap { $0.mediaOwnerTypes })
            if mediaOwnerTypes.isEmpty == false,
               try db.tableExists("media_attachments"),
               try db.tableExists("media_attachments", in: Self.incomingSchemaName) {
                let columns = try commonColumns(db: db, tableName: "media_attachments")
                if columns.contains("id"), columns.contains("owner_type"), columns.contains("owner_id") {
                    importedRowCount += try mergeMissingRows(
                        db: db,
                        tableName: "media_attachments",
                        columns: columns,
                        whereClause: try mediaAttachmentImportWhereClause(db: db, ownerTypes: mediaOwnerTypes)
                    )
                }
            }

            return importedRowCount
        }
    }

    nonisolated private func mergeMissingRows(
        db: Database,
        tableName: String,
        columns: [String],
        whereClause: String? = nil
    ) throws -> Int {
        let quotedColumns = columns.map(Self.quotedIdentifier).joined(separator: ", ")
        let selectedColumns = columns
            .map { selectExpression(for: $0, tableName: tableName) }
            .joined(separator: ", ")
        let extraWhere = whereClause.map { " AND \($0)" } ?? ""
        let before = db.totalChangesCount

        // 增量导入只补主键不存在的记录；本机已有记录不更新、不删除，贴近后续局域网/服务器同步的缺失数据补齐语义。
        try db.execute(sql: """
            INSERT OR IGNORE INTO \(Self.quotedIdentifier(tableName)) (\(quotedColumns))
            SELECT \(selectedColumns)
            FROM \(Self.incomingSchemaName).\(Self.quotedIdentifier(tableName)) AS src
            WHERE NOT EXISTS (
                SELECT 1
                FROM \(Self.quotedIdentifier(tableName)) AS dst
                WHERE dst.\(Self.quotedIdentifier("id")) = src.\(Self.quotedIdentifier("id"))
            )\(extraWhere)
            """)

        return db.totalChangesCount - before
    }

    nonisolated private func businessTableImportWhereClause(
        db: Database,
        tableName: String,
        columns: [String]
    ) throws -> String? {
        if tableName == "diary_links",
           columns.contains("diary_id"),
           try db.tableExists("diary_entries") {
            return """
                EXISTS (
                    SELECT 1
                    FROM \(Self.quotedIdentifier("diary_entries"))
                    WHERE \(Self.quotedIdentifier("diary_entries")).\(Self.quotedIdentifier("id")) = src.\(Self.quotedIdentifier("diary_id"))
                      AND \(Self.quotedIdentifier("diary_entries")).\(Self.quotedIdentifier("deleted_at")) IS NULL
                )
                """
        }

        return nil
    }

    nonisolated private func selectExpression(for columnName: String, tableName: String) -> String {
        if columnName == "sync_status" {
            return "\(SyncStatus.synced.rawValue)"
        }

        if tableName == "food_entries", columnName == "bill_id" {
            // 饮食记录到账单是可选关联；只导入饮食、不导入账单时，缺失的账单引用置空，避免外键失败导致整批导入中断。
            return """
                CASE
                    WHEN src.\(Self.quotedIdentifier(columnName)) IS NULL THEN NULL
                    WHEN EXISTS (
                        SELECT 1
                        FROM \(Self.quotedIdentifier("bills"))
                        WHERE \(Self.quotedIdentifier("bills")).\(Self.quotedIdentifier("id")) = src.\(Self.quotedIdentifier(columnName))
                    ) THEN src.\(Self.quotedIdentifier(columnName))
                    ELSE NULL
                END
                """
        }

        if tableName == "food_entries", columnName == "bill_ids_json" {
            // 多账单关联不设外键；增量导入时仍过滤掉当前库不存在的账单，避免列表长期显示“同步中”。
            return """
                COALESCE((
                    SELECT json_group_array(value)
                    FROM json_each(src.\(Self.quotedIdentifier(columnName)))
                    WHERE EXISTS (
                        SELECT 1
                        FROM \(Self.quotedIdentifier("bills"))
                        WHERE \(Self.quotedIdentifier("bills")).\(Self.quotedIdentifier("id")) = value
                    )
                ), '[]')
                """
        }

        return "src.\(Self.quotedIdentifier(columnName))"
    }

    nonisolated private func mediaAttachmentImportWhereClause(
        db: Database,
        ownerTypes: Set<String>
    ) throws -> String {
        var ownerPredicates: [String] = []

        if ownerTypes.contains(MediaAttachmentOwnerType.foodEntry.rawValue),
           try db.tableExists("food_entries") {
            ownerPredicates.append("""
                (
                    src.\(Self.quotedIdentifier("owner_type")) = \(Self.quotedStringLiteral(MediaAttachmentOwnerType.foodEntry.rawValue))
                    AND EXISTS (
                        SELECT 1
                        FROM \(Self.quotedIdentifier("food_entries"))
                        WHERE \(Self.quotedIdentifier("food_entries")).\(Self.quotedIdentifier("id")) = src.\(Self.quotedIdentifier("owner_id"))
                          AND \(Self.quotedIdentifier("food_entries")).\(Self.quotedIdentifier("deleted_at")) IS NULL
                    )
                )
                """)
        }

        if ownerTypes.contains(MediaAttachmentOwnerType.diary.rawValue),
           try db.tableExists("diary_entries") {
            ownerPredicates.append("""
                (
                    src.\(Self.quotedIdentifier("owner_type")) = \(Self.quotedStringLiteral(MediaAttachmentOwnerType.diary.rawValue))
                    AND EXISTS (
                        SELECT 1
                        FROM \(Self.quotedIdentifier("diary_entries"))
                        WHERE \(Self.quotedIdentifier("diary_entries")).\(Self.quotedIdentifier("id")) = src.\(Self.quotedIdentifier("owner_id"))
                          AND \(Self.quotedIdentifier("diary_entries")).\(Self.quotedIdentifier("deleted_at")) IS NULL
                    )
                )
                """)
        }

        guard ownerPredicates.isEmpty == false else {
            return "0"
        }

        return "(\(ownerPredicates.joined(separator: " OR ")))"
    }

    nonisolated private func mediaAssetImportWhereClause(
        db: Database,
        scopes: Set<AppDataImportScope>
    ) throws -> String? {
        var references: [String] = []
        let mediaOwnerTypes = Set(scopes.flatMap(\.mediaOwnerTypes))

        if mediaOwnerTypes.isEmpty == false,
           try db.tableExists("media_attachments", in: Self.incomingSchemaName),
           try columnNames(db: db, tableName: "media_attachments", schemaName: Self.incomingSchemaName).contains("asset_id") {
            references.append("""
                SELECT DISTINCT src_attachment.\(Self.quotedIdentifier("asset_id"))
                FROM \(Self.incomingSchemaName).\(Self.quotedIdentifier("media_attachments")) AS src_attachment
                WHERE src_attachment.\(Self.quotedIdentifier("asset_id")) IS NOT NULL
                  AND \(try mediaAttachmentAssetOwnerWhereClause(db: db, ownerTypes: mediaOwnerTypes))
                """)
        }

        for reference in try imageAssetReferences(db: db, scopes: scopes) {
            references.append("""
                SELECT DISTINCT src_reference.\(Self.quotedIdentifier(reference.column))
                FROM \(Self.incomingSchemaName).\(Self.quotedIdentifier(reference.table)) AS src_reference
                WHERE src_reference.\(Self.quotedIdentifier(reference.column)) IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM \(Self.quotedIdentifier(reference.table)) AS dst_reference
                      WHERE dst_reference.\(Self.quotedIdentifier("id")) = src_reference.\(Self.quotedIdentifier("id"))
                        AND dst_reference.\(Self.quotedIdentifier("deleted_at")) IS NULL
                  )
                """)
        }

        guard references.isEmpty == false else { return nil }
        return "src.\(Self.quotedIdentifier("id")) IN (\(references.joined(separator: " UNION ")))"
    }

    nonisolated private func mediaAttachmentAssetOwnerWhereClause(
        db: Database,
        ownerTypes: Set<String>
    ) throws -> String {
        var ownerPredicates: [String] = []

        if ownerTypes.contains(MediaAttachmentOwnerType.foodEntry.rawValue),
           try db.tableExists("food_entries") {
            ownerPredicates.append("""
                (
                    src_attachment.\(Self.quotedIdentifier("owner_type")) = \(Self.quotedStringLiteral(MediaAttachmentOwnerType.foodEntry.rawValue))
                    AND EXISTS (
                        SELECT 1
                        FROM \(Self.quotedIdentifier("food_entries"))
                        WHERE \(Self.quotedIdentifier("food_entries")).\(Self.quotedIdentifier("id")) = src_attachment.\(Self.quotedIdentifier("owner_id"))
                          AND \(Self.quotedIdentifier("food_entries")).\(Self.quotedIdentifier("deleted_at")) IS NULL
                    )
                )
                """)
        }

        if ownerTypes.contains(MediaAttachmentOwnerType.diary.rawValue),
           try db.tableExists("diary_entries") {
            ownerPredicates.append("""
                (
                    src_attachment.\(Self.quotedIdentifier("owner_type")) = \(Self.quotedStringLiteral(MediaAttachmentOwnerType.diary.rawValue))
                    AND EXISTS (
                        SELECT 1
                        FROM \(Self.quotedIdentifier("diary_entries"))
                        WHERE \(Self.quotedIdentifier("diary_entries")).\(Self.quotedIdentifier("id")) = src_attachment.\(Self.quotedIdentifier("owner_id"))
                          AND \(Self.quotedIdentifier("diary_entries")).\(Self.quotedIdentifier("deleted_at")) IS NULL
                    )
                )
                """)
        }

        guard ownerPredicates.isEmpty == false else { return "0" }
        return "(\(ownerPredicates.joined(separator: " OR ")))"
    }

    nonisolated private func imageAssetReferences(
        db: Database,
        scopes: Set<AppDataImportScope>
    ) throws -> [(table: String, column: String)] {
        let selectedTables = Set(scopes.flatMap(\.tableNames))
        var references: [(table: String, column: String)] = []

        for reference in Self.imageAssetReferenceColumns where selectedTables.contains(reference.table) {
            guard try db.tableExists(reference.table),
                  try db.tableExists(reference.table, in: Self.incomingSchemaName),
                  try db.columns(in: reference.table).contains(where: { $0.name == reference.column }),
                  try columnNames(db: db, tableName: reference.table, schemaName: Self.incomingSchemaName).contains(reference.column)
            else {
                continue
            }

            references.append(reference)
        }

        return references
    }

    nonisolated private func commonColumns(db: Database, tableName: String) throws -> [String] {
        let destinationColumns = try columnNames(db: db, tableName: tableName, schemaName: nil)
        let sourceColumns = try columnNames(db: db, tableName: tableName, schemaName: Self.incomingSchemaName)
        let sourceColumnSet = Set(sourceColumns)
        return destinationColumns.filter { sourceColumnSet.contains($0) }
    }

    nonisolated private func columnNames(db: Database, tableName: String, schemaName: String?) throws -> [String] {
        let pragmaPrefix = schemaName.map { "\(Self.quotedIdentifier($0))." } ?? ""
        let rows = try Row.fetchAll(db, sql: "PRAGMA \(pragmaPrefix)table_info(\(Self.quotedStringLiteral(tableName)))")
        return rows.map { row in
            let name: String = row["name"]
            return name
        }
    }

    nonisolated private func writeMetadata(_ metadata: AppDataTransferMetadata, to backupURL: URL) throws {
        let queue = try DatabaseQueue(path: backupURL.path(percentEncoded: false))
        defer {
            try? queue.close()
        }

        let json = try metadata.encodedJSONString()

        try queue.write { db in
            try db.execute(sql: """
                CREATE TABLE IF NOT EXISTS \(Self.metadataTableName) (
                    key TEXT NOT NULL PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
            )
            try db.execute(
                sql: """
                INSERT INTO \(Self.metadataTableName) (key, value)
                VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """,
                arguments: [Self.metadataKey, json]
            )
        }
    }

    @discardableResult
    nonisolated private func validateBackup(at url: URL) throws -> AppDataTransferMetadata {
        let queue: DatabaseQueue
        do {
            queue = try DatabaseQueue(path: url.path(percentEncoded: false), configuration: readOnlyConfiguration)
        } catch {
            throw AppDataTransferError.unreadableBackup
        }
        defer {
            try? queue.close()
        }

        return try queue.read { db in
            let integrity = try String.fetchOne(db, sql: "PRAGMA integrity_check") ?? ""
            guard integrity == "ok" else {
                throw AppDataTransferError.unreadableBackup
            }

            guard try db.tableExists(Self.metadataTableName),
                  let json = try String.fetchOne(
                    db,
                    sql: "SELECT value FROM \(Self.metadataTableName) WHERE key = ?",
                    arguments: [Self.metadataKey]
                  )
            else {
                throw AppDataTransferError.missingMetadata
            }

            let metadata = try AppDataTransferMetadata.decoded(fromJSONString: json)
            guard metadata.formatVersion <= AppDataTransferMetadata.currentFormatVersion else {
                throw AppDataTransferError.unsupportedFormatVersion(metadata.formatVersion)
            }

            // 新 schema 可能包含当前 App 不认识的表或列，直接导入会造成数据丢失或启动失败。
            guard metadata.databaseSchemaVersion <= AppMigrator.currentSchemaVersion else {
                throw AppDataTransferError.newerDatabaseSchema(
                    backupVersion: metadata.databaseSchemaVersion,
                    currentVersion: AppMigrator.currentSchemaVersion
                )
            }

            return metadata
        }
    }

    nonisolated private var readOnlyConfiguration: Configuration {
        var configuration = Configuration()
        configuration.readonly = true
        return configuration
    }

    nonisolated private func copyToTemporaryLocation(_ sourceURL: URL) throws -> URL {
        // 文件导入器返回的 URL 可能是安全作用域资源；复制到沙盒后再让 GRDB 打开，避免权限在校验/恢复期间失效。
        let didAccess = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        let destinationURL = try makeTemporaryBackupURL(prefix: "recoow-import")
        try FileManager.default.copyItem(at: sourceURL, to: destinationURL)
        return destinationURL
    }

    nonisolated private func makeTemporaryBackupURL(prefix: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "RecoowDataTransfer", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appending(path: "\(prefix)-\(UUID().uuidString).recoowbackup")
    }

    nonisolated private func makePersistentRollbackURL() throws -> URL {
        let appSupport = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = appSupport.appending(path: "DataBackups", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appending(path: "rollback-\(Self.timestampForFilename()).recoowbackup")
    }

    nonisolated private static func timestampForFilename() -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }

    nonisolated private static func quotedIdentifier(_ value: String) -> String {
        "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\""
    }

    nonisolated private static func quotedStringLiteral(_ value: String) -> String {
        "'\(value.replacingOccurrences(of: "'", with: "''"))'"
    }

    nonisolated private static var imageAssetReferenceColumns: [(table: String, column: String)] {
        [
            ("media_attachments", "asset_id"),
            ("reminders", "image_asset_id"),
            ("bills", "image_asset_id"),
            ("stored_items", "image_asset_id"),
            ("decision_options", "image_asset_id"),
            ("decision_choice_records", "option_image_asset_id")
        ]
    }

    nonisolated private static var syncableTableNames: [String] {
        [
            "decision_collections",
            "decision_options",
            "item_categories",
            "stored_items",
            "tracks",
            "track_points",
            "track_segments",
            "decision_choice_records",
            "reminders",
            "bills",
            "food_entries",
            "food_day_records",
            "anniversaries",
            "diary_entries",
            "diary_tags",
            "diary_links",
            "media_assets",
            "media_attachments"
        ]
    }
}
