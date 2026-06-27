import GRDB

/// V15：补齐早期本地 v14 数据库缺失的附件排序列。
enum V15MediaAttachmentSortOrderSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v15_media_attachment_sort_order_schema") { db in
            let columnRows = try Row.fetchAll(db, sql: "PRAGMA table_info(media_attachments)")
            guard columnRows.isEmpty == false else { return }

            let hasSortOrder = columnRows.contains { row in
                let name: String = row["name"]
                return name == "sort_order"
            }

            guard hasSortOrder == false else { return }

            try db.alter(table: "media_attachments") { t in
                t.add(column: "sort_order", .integer).notNull().defaults(to: 0)
            }
        }
    }
}
