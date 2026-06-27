import Foundation
import GRDB

/// V16：开发期重建日记相关 schema，不保留旧日记数据。
enum V16DiaryMediaSchemaReset {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v16_diary_media_schema_reset") { db in
            try removePendingChanges(in: db)

            try CurrentMediaAttachmentDatabaseSchema.drop(in: db)
            try CurrentDiaryDatabaseSchema.drop(in: db)

            try CurrentDiaryDatabaseSchema.create(in: db)
            try CurrentMediaAttachmentDatabaseSchema.create(in: db)
        }
    }

    nonisolated private static func removePendingChanges(in db: Database) throws {
        let tableNames = CurrentDiaryDatabaseSchema.tableNames + CurrentMediaAttachmentDatabaseSchema.tableNames
        try db.execute(
            sql: "DELETE FROM change_log WHERE entity_table IN \(tableNames.sqlInList)"
        )
    }
}

private extension Array where Element == String {
    nonisolated var sqlInList: String {
        let escapedValues = map { value in
            "'\(value.replacingOccurrences(of: "'", with: "''"))'"
        }
        return "(\(escapedValues.joined(separator: ", ")))"
    }
}
