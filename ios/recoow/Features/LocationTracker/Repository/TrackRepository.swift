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

    func finishInterruptedTracks() throws -> [Track] {
        try database.writer.write { db in
            let tracks = try Track
                .filter(Column("deleted_at") == nil)
                .filter(Column("ended_at") == nil)
                .filter(Column("device_id") == deviceID)
                .order(Column("started_at").desc)
                .fetchAll(db)
            var finishedTracks: [Track] = []

            for var track in tracks {
                let points = try TrackPoint
                    .filter(Column("track_id") == track.id)
                    .filter(Column("deleted_at") == nil)
                    .order(Column("timestamp_ms").asc)
                    .fetchAll(db)
                let metrics = Self.metrics(for: points)
                let endedAt = max(track.startedAt, points.last?.timestampMilliseconds ?? track.updatedAt)
                let duration = max(1, (endedAt - track.startedAt) / 1000)

                track.endedAt = endedAt
                track.updatedAt = SyncableTimestamp.nowMilliseconds()
                track.distanceMeters = metrics.distanceMeters
                track.durationSeconds = duration
                track.averageSpeedMetersPerSecond = metrics.distanceMeters > 0 ? metrics.distanceMeters / Double(duration) : nil
                track.maxSpeedMetersPerSecond = metrics.maxSpeedMetersPerSecond
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
                finishedTracks.append(track)
            }

            return finishedTracks
        }
    }

    func updateTrackDetails(id: String, name: String, note: String?) throws -> Track? {
        try database.writer.write { db in
            guard var track = try Track.fetchOne(db, key: id), track.deletedAt == nil else {
                return nil
            }

            track.name = name
            track.note = note
            track.updatedAt = SyncableTimestamp.nowMilliseconds()
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

    func deleteTracks(ids: [String]) throws {
        guard ids.isEmpty == false else { return }

        try database.writer.write { db in
            for id in ids {
                try deleteTrack(id: id, db: db)
            }
        }
    }

    func fetchTrack(id: String) throws -> Track? {
        try database.reader.read { db in
            try Track
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    func fetchRecentTracks(limit: Int = 50) throws -> [Track] {
        try database.reader.read { db in
            try Track
                .filter(Column("deleted_at") == nil)
                .order(Column("started_at").desc, Column("id").desc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    func fetchPoints(trackID: String) throws -> [TrackPoint] {
        try database.reader.read { db in
            try TrackPoint
                .filter(Column("track_id") == trackID)
                .filter(Column("deleted_at") == nil)
                .order(Column("timestamp_ms").asc)
                .fetchAll(db)
        }
    }

    func fetchPointCounts(trackIDs: [String]) throws -> [String: Int] {
        guard trackIDs.isEmpty == false else { return [:] }

        return try database.reader.read { db in
            var counts: [String: Int] = [:]

            for trackID in trackIDs {
                counts[trackID] = try TrackPoint
                    .filter(Column("track_id") == trackID)
                    .filter(Column("deleted_at") == nil)
                    .fetchCount(db)
            }

            return counts
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

    private func deleteTrack(id: String, db: Database) throws {
        guard var track = try Track.fetchOne(db, key: id), track.deletedAt == nil else {
            return
        }

        let deletedAt = SyncableTimestamp.nowMilliseconds()
        track.deletedAt = deletedAt
        track.updatedAt = deletedAt
        track.syncStatus = .pending

        try track.update(db)
        try changeLogRepository.append(
            db: db,
            table: Track.databaseTableName,
            entityID: track.id,
            operation: .delete,
            payload: track,
            clientTimestampMilliseconds: track.updatedAt
        )

        let points = try TrackPoint
            .filter(Column("track_id") == id)
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)

        for var point in points {
            point.deletedAt = deletedAt
            point.updatedAt = deletedAt
            point.syncStatus = .pending

            try point.update(db)
            try changeLogRepository.append(
                db: db,
                table: TrackPoint.databaseTableName,
                entityID: point.id,
                operation: .delete,
                payload: point,
                clientTimestampMilliseconds: point.updatedAt
            )
        }
    }

    private static func metrics(for points: [TrackPoint]) -> (distanceMeters: Double, maxSpeedMetersPerSecond: Double?) {
        let distance = zip(points, points.dropFirst()).reduce(0) { partialResult, pair in
            partialResult + distanceMeters(from: pair.0, to: pair.1)
        }

        return (
            distanceMeters: distance,
            maxSpeedMetersPerSecond: points.compactMap(\.speedMetersPerSecond).max()
        )
    }

    private static func distanceMeters(from start: TrackPoint, to end: TrackPoint) -> Double {
        let earthRadius = 6_371_000.0
        let startLatitude = radians(from: start.latitude)
        let endLatitude = radians(from: end.latitude)
        let latitudeDelta = radians(from: end.latitude - start.latitude)
        let longitudeDelta = radians(from: end.longitude - start.longitude)
        let a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2)
            + cos(startLatitude) * cos(endLatitude)
            * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    private static func radians(from degrees: Double) -> Double {
        degrees * .pi / 180
    }
}
