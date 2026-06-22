import GRDB

/// 数据库迁移注册中心。所有版本按时间顺序追加，禁止修改已发布迁移。
enum AppMigrator {
    static func makeMigrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()
        V1InitialSchema.register(in: &migrator)
        V2ToolsSchema.register(in: &migrator)
        V3DecisionChoiceHistorySchema.register(in: &migrator)
        V4RemindersSchema.register(in: &migrator)
        V5BillsSchema.register(in: &migrator)
        V6BillTransactionTypeSchema.register(in: &migrator)
        V7CheckInsSchema.register(in: &migrator)
        V8CheckInImportedProgressSchema.register(in: &migrator)
        V9CheckInOccurrenceCompletionSchema.register(in: &migrator)
        V10AnniversariesSchema.register(in: &migrator)
        V11AnniversaryDateCalendarSchema.register(in: &migrator)
        V12CheckInCompletionRecordsSchema.register(in: &migrator)
        V13DiarySchema.register(in: &migrator)
        V14MediaAttachmentsSchema.register(in: &migrator)
        V15MediaAttachmentSortOrderSchema.register(in: &migrator)
        V16DiaryMediaSchemaReset.register(in: &migrator)
        V17BillTransportLocationsSchema.register(in: &migrator)
        return migrator
    }

    static func migrate(_ writer: DatabaseWriter) throws {
        try makeMigrator().migrate(writer)
    }
}
