import Foundation
import GRDB

/// LocationTracker 的数据访问层。所有写操作都在同一事务内写业务表和 outbox。
final class TrackRepository: @unchecked Sendable {
    private let database: AppDatabase
    private let changeLogRepository: ChangeLogRepository
    private let deviceIdentifier: DeviceIdentifier

    init(
        database: AppDatabase,
        changeLogRepository: ChangeLogRepository,
        deviceIdentifier: DeviceIdentifier
    ) {
        self.database = database
        self.changeLogRepository = changeLogRepository
        self.deviceIdentifier = deviceIdentifier
    }

    var deviceID: String {
        deviceIdentifier.value
    }

    func insertTrack(_ track: Track) throws {
        try database.writer.write { db in
            try track.insert(db)
            try changeLogRepository.append(
                db: db,
                table: Track.databaseTableName,
                entityID: track.id,
                operation: .insert,
                payload: track,
                clientTimestampMilliseconds: track.updatedAt
            )
        }
    }

    func appendPoints(_ points: [TrackPoint]) throws {
        guard points.isEmpty == false else { return }

        try database.writer.write { db in
            for point in points {
                try point.insert(db)
                try changeLogRepository.append(
                    db: db,
                    table: TrackPoint.databaseTableName,
                    entityID: point.id,
                    operation: .insert,
                    payload: point,
                    clientTimestampMilliseconds: point.updatedAt
                )
            }
        }
    }

    func finishTrack(
        id: String,
        endedAt: Int64,
        distanceMeters: Double,
        durationSeconds: Int64,
        averageSpeedMetersPerSecond: Double?,
        maxSpeedMetersPerSecond: Double?
    ) throws -> Track? {
        try database.writer.write { db in
            guard var track = try Track.fetchOne(db, key: id) else { return nil }
            track.endedAt = endedAt
            track.updatedAt = endedAt
            track.distanceMeters = distanceMeters
            track.durationSeconds = durationSeconds
            track.averageSpeedMetersPerSecond = averageSpeedMetersPerSecond
            track.maxSpeedMetersPerSecond = maxSpeedMetersPerSecond
            track.syncStatus = .pending

            try track.update(db)
            try changeLogRepository.append(
                db: db,
                table: Track.databaseTableName,
                entityID: track.id,
                operation: .update,
                payload: track,
                clientTimestampMilliseconds: track.updatedAt
            )
            return track
        }
    }

    func fetchTrack(id: String) throws -> Track? {
        try database.reader.read { db in
            try Track.fetchOne(db, key: id)
        }
    }

    func fetchPoints(trackID: String) throws -> [TrackPoint] {
        try database.reader.read { db in
            try TrackPoint
                .filter(Column("track_id") == trackID)
                .order(Column("timestamp_ms").asc)
                .fetchAll(db)
        }
    }

    func observeTracks() -> AsyncStream<Result<[Track], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try Track
                    .filter(Column("deleted_at") == nil)
                    .order(Column("started_at").desc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { tracks in
                    continuation.yield(.success(tracks))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }
}
