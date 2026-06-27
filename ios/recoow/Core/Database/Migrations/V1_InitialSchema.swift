import GRDB

/// V1 初始 schema：轨迹、轨迹点和同步 outbox。
enum V1InitialSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v1_initial_schema") { db in
            try db.create(table: "tracks") { t in
                t.syncMetadata()
                t.column("name", .text).notNull()
                t.column("started_at", .integer).notNull()
                t.column("ended_at", .integer)
                t.column("desired_accuracy_m", .integer).notNull()
                t.column("distance_m", .double).notNull().defaults(to: 0)
                t.column("duration_s", .integer).notNull().defaults(to: 0)
                t.column("avg_speed_mps", .double)
                t.column("max_speed_mps", .double)
                t.column("note", .text)
            }

            try db.create(index: "idx_tracks_updated_at", on: "tracks", columns: ["updated_at"])
            try db.create(index: "idx_tracks_started_at_desc", on: "tracks", columns: ["started_at"], options: [.ifNotExists])

            try db.create(table: "track_points") { t in
                t.syncMetadata()
                t.column("track_id", .text)
                    .notNull()
                    .references("tracks", onDelete: .cascade)
                t.column("latitude", .double).notNull()
                t.column("longitude", .double).notNull()
                t.column("altitude", .double)
                t.column("horizontal_acc", .double)
                t.column("vertical_acc", .double)
                t.column("speed_mps", .double)
                t.column("course_deg", .double)
                t.column("timestamp_ms", .integer).notNull()
            }

            try db.create(index: "idx_track_points_track_id_timestamp_ms", on: "track_points", columns: ["track_id", "timestamp_ms"])

            try db.create(table: "change_log") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("entity_table", .text).notNull()
                t.column("entity_id", .text).notNull()
                t.column("operation", .text).notNull().check(sql: "operation IN ('insert','update','delete')")
                t.column("payload_json", .text).notNull()
                t.column("client_ts_ms", .integer).notNull()
                t.column("attempt_count", .integer).notNull().defaults(to: 0)
                t.column("last_error", .text)
                t.column("synced_at_ms", .integer)
            }

            try db.create(index: "idx_change_log_synced_at_ms", on: "change_log", columns: ["synced_at_ms"])
        }
    }
}
