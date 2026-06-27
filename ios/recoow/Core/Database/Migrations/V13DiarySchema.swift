import GRDB

/// V13：日记与跨模块关联记录。
enum V13DiarySchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v13_diary_schema") { db in
            try CurrentDiaryDatabaseSchema.create(in: db)
        }
    }
}
