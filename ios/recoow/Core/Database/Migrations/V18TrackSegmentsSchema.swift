import GRDB

enum V18TrackSegmentsSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v18_track_segments_schema") { db in
            try db.alter(table: "tracks") { t in
                t.add(column: "status", .text).notNull().defaults(to: TrackRecordingStatus.finished.rawValue)
            }

            try db.execute(
                sql: """
                UPDATE tracks
                SET status = CASE
                    WHEN ended_at IS NULL THEN ?
                    ELSE ?
                END
                """,
                arguments: [TrackRecordingStatus.paused.rawValue, TrackRecordingStatus.finished.rawValue]
            )

            try db.create(table: "track_segments") { t in
                t.syncMetadata()
                t.column("track_id", .text)
                    .notNull()
                    .references("tracks", onDelete: .cascade)
                t.column("started_at", .integer).notNull()
                t.column("ended_at", .integer).notNull()
                t.column("distance_m", .double).notNull().defaults(to: 0)
                t.column("duration_s", .integer).notNull().defaults(to: 0)
                t.column("avg_speed_mps", .double)
                t.column("max_speed_mps", .double)
                t.column("motion_type", .text).notNull().defaults(to: TrackMotionType.unknown.rawValue)
                t.column("source", .text).notNull().defaults(to: TrackSegmentSource.auto.rawValue)
                t.column("confidence", .double).notNull().defaults(to: 0)
            }

            try db.create(
                index: "idx_track_segments_track_id_started_at",
                on: "track_segments",
                columns: ["track_id", "started_at"]
            )
        }
    }
}
