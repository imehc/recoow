import GRDB

/// 数据库迁移注册中心。所有版本按时间顺序追加，禁止修改已发布迁移。
enum AppMigrator {
    nonisolated static let currentSchemaVersion = 27

    nonisolated static func makeMigrator() -> DatabaseMigrator {
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
        V18TrackSegmentsSchema.register(in: &migrator)
        V19BillTransportLinesSchema.register(in: &migrator)
        V20FoodJournalSchema.register(in: &migrator)
        V21FoodEntryBillLinkSchema.register(in: &migrator)
        V22FoodDayRecordsSchema.register(in: &migrator)
        V23BillSettlementSchema.register(in: &migrator)
        V24BillRedeemedAtSchema.register(in: &migrator)
        V25BillRefundReasonSchema.register(in: &migrator)
        V26MediaAssetsSchema.register(in: &migrator)
        V27ImageAssetReferencesSchema.register(in: &migrator)
        return migrator
    }

    nonisolated static func migrate(_ writer: DatabaseWriter) throws {
        try makeMigrator().migrate(writer)
    }
}
