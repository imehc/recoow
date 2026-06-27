import GRDB

enum CurrentDiaryDatabaseSchema {
    nonisolated static let tableNames = [
        "diary_entries",
        "diary_tags",
        "diary_links"
    ]

    nonisolated static func create(in db: Database) throws {
        try db.create(table: "diary_entries") { t in
            t.syncMetadata()
            t.column("title", .text).notNull()
            t.column("content", .text).notNull()
            t.column("mood", .text).notNull()
            t.column("tags_json", .text).notNull().defaults(to: "[]")
            t.column("occurred_at", .integer).notNull()
            t.column("latitude", .double)
            t.column("longitude", .double)
            t.column("horizontal_accuracy", .double)
        }

        try db.create(index: "idx_diary_entries_occurred_at", on: "diary_entries", columns: ["occurred_at"])
        try db.create(index: "idx_diary_entries_updated_at", on: "diary_entries", columns: ["updated_at"])

        try db.create(table: "diary_tags") { t in
            t.syncMetadata()
            t.column("name", .text).notNull()
            t.column("note", .text)
        }

        try db.create(index: "idx_diary_tags_name", on: "diary_tags", columns: ["name"])
        try db.create(index: "idx_diary_tags_updated_at", on: "diary_tags", columns: ["updated_at"])

        try db.create(table: "diary_links") { t in
            t.syncMetadata()
            t.column("diary_id", .text)
                .notNull()
                .references("diary_entries", onDelete: .cascade)
            t.column("source_type", .text).notNull()
            t.column("source_id", .text).notNull()
            t.column("source_title", .text).notNull()
            t.column("source_subtitle", .text)
            t.column("source_icon", .text).notNull()
            t.column("source_occurred_at", .integer)
            t.column("snapshot_json", .text)
        }

        try db.create(index: "idx_diary_links_diary_id", on: "diary_links", columns: ["diary_id"])
        try db.create(index: "idx_diary_links_source", on: "diary_links", columns: ["source_type", "source_id"])
        try db.create(index: "idx_diary_links_updated_at", on: "diary_links", columns: ["updated_at"])
    }

    nonisolated static func drop(in db: Database) throws {
        try db.execute(sql: "DROP TABLE IF EXISTS diary_links")
        try db.execute(sql: "DROP TABLE IF EXISTS diary_tags")
        try db.execute(sql: "DROP TABLE IF EXISTS diary_entries")
    }
}
