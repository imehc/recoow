import GRDB

/// V3：记录“选什么”的随机结果历史。
enum V3DecisionChoiceHistorySchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v3_decision_choice_history_schema") { db in
            try db.create(table: "decision_choice_records") { t in
                t.syncMetadata()
                t.column("collection_id", .text).notNull()
                t.column("collection_title", .text).notNull()
                t.column("option_id", .text).notNull()
                t.column("option_title", .text).notNull()
                t.column("option_detail", .text)
                t.column("option_custom_info", .text)
                t.column("option_image_data", .blob)
                t.column("selected_at", .integer).notNull()
            }

            try db.create(
                index: "idx_decision_choice_records_collection_id",
                on: "decision_choice_records",
                columns: ["collection_id"]
            )
            try db.create(
                index: "idx_decision_choice_records_selected_at",
                on: "decision_choice_records",
                columns: ["selected_at"]
            )
        }
    }
}
