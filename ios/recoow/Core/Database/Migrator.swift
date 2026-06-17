import GRDB

/// 数据库迁移注册中心。所有版本按时间顺序追加，禁止修改已发布迁移。
enum AppMigrator {
    static func makeMigrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()
        V1InitialSchema.register(in: &migrator)
        return migrator
    }

    static func migrate(_ writer: DatabaseWriter) throws {
        try makeMigrator().migrate(writer)
    }
}
