import Foundation
import GRDB

struct SoftDeletedDataCleanupResult: Sendable {
    let deletedRowCount: Int
    let retainedRowCount: Int
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

    nonisolated private func countRows(db: Database, table: String, condition: String) throws -> Int {
        try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM \(table) WHERE \(condition)") ?? 0
    }
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
                WHERE food_entries.bill_id = bills.id
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
