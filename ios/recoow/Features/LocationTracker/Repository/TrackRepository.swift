import CoreLocation
import Foundation
import GRDB

enum TrackSegmentMergeDirection {
    case previous
    case next
}

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
            track.statusRawValue = TrackRecordingStatus.finished.rawValue
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

    func pauseTrack(
        id: String,
        distanceMeters: Double,
        durationSeconds: Int64,
        averageSpeedMetersPerSecond: Double?,
        maxSpeedMetersPerSecond: Double?
    ) throws -> Track? {
        try database.writer.write { db in
            guard var track = try Track.fetchOne(db, key: id), track.deletedAt == nil else { return nil }
            track.updatedAt = SyncableTimestamp.nowMilliseconds()
            track.distanceMeters = distanceMeters
            track.durationSeconds = durationSeconds
            track.averageSpeedMetersPerSecond = averageSpeedMetersPerSecond
            track.maxSpeedMetersPerSecond = maxSpeedMetersPerSecond
            track.statusRawValue = TrackRecordingStatus.paused.rawValue
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

    func resumeTrack(id: String) throws -> Track? {
        try database.writer.write { db in
            guard var track = try Track.fetchOne(db, key: id), track.deletedAt == nil else { return nil }
            track.updatedAt = SyncableTimestamp.nowMilliseconds()
            track.statusRawValue = TrackRecordingStatus.recording.rawValue
            track.endedAt = nil
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

    func pauseInterruptedTracks() throws -> [Track] {
        try database.writer.write { db in
            let tracks = try Track
                .filter(Column("deleted_at") == nil)
                .filter(Column("ended_at") == nil)
                .filter(Column("device_id") == deviceID)
                .filter(Column("status") == TrackRecordingStatus.recording.rawValue)
                .order(Column("started_at").desc)
                .fetchAll(db)
            var pausedTracks: [Track] = []

            for var track in tracks {
                let points = try TrackPoint
                    .filter(Column("track_id") == track.id)
                    .filter(Column("deleted_at") == nil)
                    .order(Column("timestamp_ms").asc)
                    .fetchAll(db)
                let metrics = TrackSegmentAnalyzer.metrics(for: points)

                track.updatedAt = SyncableTimestamp.nowMilliseconds()
                track.distanceMeters = metrics.distanceMeters
                track.durationSeconds = metrics.durationSeconds
                track.averageSpeedMetersPerSecond = metrics.averageSpeedMetersPerSecond
                track.maxSpeedMetersPerSecond = metrics.maxSpeedMetersPerSecond
                track.statusRawValue = TrackRecordingStatus.paused.rawValue
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
                pausedTracks.append(track)
            }

            return pausedTracks
        }
    }

    func fetchPausedTrack() throws -> Track? {
        try database.reader.read { db in
            try Track
                .filter(Column("deleted_at") == nil)
                .filter(Column("device_id") == deviceID)
                .filter(Column("status") == TrackRecordingStatus.paused.rawValue)
                .order(Column("updated_at").desc, Column("started_at").desc)
                .fetchOne(db)
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

    func fetchSegments(trackID: String) throws -> [TrackSegment] {
        try database.reader.read { db in
            try TrackSegment
                .filter(Column("track_id") == trackID)
                .filter(Column("deleted_at") == nil)
                .order(Column("started_at").asc, Column("id").asc)
                .fetchAll(db)
        }
    }

    func replaceAutoSegments(trackID: String, with segments: [TrackSegment]) throws {
        try database.writer.write { db in
            let now = SyncableTimestamp.nowMilliseconds()
            let existingAutoSegments = try TrackSegment
                .filter(Column("track_id") == trackID)
                .filter(Column("source") == TrackSegmentSource.auto.rawValue)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)

            for var segment in existingAutoSegments {
                segment.deletedAt = now
                segment.updatedAt = now
                segment.syncStatus = .pending

                try segment.update(db)
                try changeLogRepository.append(
                    db: db,
                    table: TrackSegment.databaseTableName,
                    entityID: segment.id,
                    operation: .delete,
                    payload: segment,
                    clientTimestampMilliseconds: segment.updatedAt
                )
            }

            for segment in segments {
                try segment.insert(db)
                try changeLogRepository.append(
                    db: db,
                    table: TrackSegment.databaseTableName,
                    entityID: segment.id,
                    operation: .insert,
                    payload: segment,
                    clientTimestampMilliseconds: segment.updatedAt
                )
            }
        }
    }

    func updateSegmentMotionType(id: String, motionType: TrackMotionType) throws -> TrackSegment? {
        try database.writer.write { db in
            guard var segment = try TrackSegment.fetchOne(db, key: id), segment.deletedAt == nil else {
                return nil
            }

            segment.motionTypeRawValue = motionType.rawValue
            segment.sourceRawValue = TrackSegmentSource.manual.rawValue
            segment.confidence = 1
            segment.updatedAt = SyncableTimestamp.nowMilliseconds()
            segment.syncStatus = .pending

            try segment.update(db)
            try changeLogRepository.append(
                db: db,
                table: TrackSegment.databaseTableName,
                entityID: segment.id,
                operation: .update,
                payload: segment,
                clientTimestampMilliseconds: segment.updatedAt
            )
            return segment
        }
    }

    func splitSegment(id: String, at timestampMilliseconds: Int64) throws -> TrackSegment? {
        try database.writer.write { db in
            guard var segment = try TrackSegment.fetchOne(db, key: id), segment.deletedAt == nil else {
                return nil
            }

            guard timestampMilliseconds > segment.startedAt,
                  timestampMilliseconds < segment.endedAt else {
                return nil
            }

            let points = try fetchPoints(trackID: segment.trackID, db: db)
            guard segmentPoints(points, from: segment.startedAt, to: timestampMilliseconds).count > 1,
                  segmentPoints(points, from: timestampMilliseconds, to: segment.endedAt).count > 1 else {
                return nil
            }

            let now = SyncableTimestamp.nowMilliseconds()
            var secondSegment = makeSegment(
                basedOn: segment,
                startedAt: timestampMilliseconds,
                endedAt: segment.endedAt,
                points: points,
                updatedAt: now
            )
            secondSegment.id = UUID().uuidString
            secondSegment.createdAt = now
            secondSegment.serverVersion = nil

            segment = makeSegment(
                basedOn: segment,
                startedAt: segment.startedAt,
                endedAt: timestampMilliseconds,
                points: points,
                updatedAt: now
            )

            try segment.update(db)
            try appendSegmentChange(db: db, segment: segment, operation: .update)
            try secondSegment.insert(db)
            try appendSegmentChange(db: db, segment: secondSegment, operation: .insert)

            return segment
        }
    }

    func mergeSegment(id: String, direction: TrackSegmentMergeDirection) throws -> TrackSegment? {
        try database.writer.write { db in
            let segments = try fetchSegments(trackIDForSegmentID: id, db: db)
            guard let index = segments.firstIndex(where: { $0.id == id }) else { return nil }

            let neighborIndex: Int
            switch direction {
            case .previous:
                guard index > segments.startIndex else { return nil }
                neighborIndex = segments.index(before: index)
            case .next:
                let nextIndex = segments.index(after: index)
                guard nextIndex < segments.endIndex else { return nil }
                neighborIndex = nextIndex
            }

            var segment = segments[index]
            var neighbor = segments[neighborIndex]
            let startedAt = min(segment.startedAt, neighbor.startedAt)
            let endedAt = max(segment.endedAt, neighbor.endedAt)
            let points = try fetchPoints(trackID: segment.trackID, db: db)
            let now = SyncableTimestamp.nowMilliseconds()

            segment = makeSegment(
                basedOn: segment,
                startedAt: startedAt,
                endedAt: endedAt,
                points: points,
                updatedAt: now
            )

            neighbor.deletedAt = now
            neighbor.updatedAt = now
            neighbor.syncStatus = .pending

            try segment.update(db)
            try appendSegmentChange(db: db, segment: segment, operation: .update)
            try neighbor.update(db)
            try appendSegmentChange(db: db, segment: neighbor, operation: .delete)

            return segment
        }
    }

    func updateSegmentBoundaries(
        id: String,
        startedAt: Int64,
        endedAt: Int64
    ) throws -> TrackSegment? {
        try database.writer.write { db in
            let segments = try fetchSegments(trackIDForSegmentID: id, db: db)
            guard let index = segments.firstIndex(where: { $0.id == id }) else { return nil }
            guard startedAt < endedAt else { return nil }

            var segment = segments[index]
            let originalStartedAt = segment.startedAt
            let originalEndedAt = segment.endedAt
            let previousIndex = index == segments.startIndex ? nil : segments.index(before: index)
            let nextIndex = segments.index(after: index) == segments.endIndex ? nil : segments.index(after: index)

            if let previousIndex {
                guard startedAt > segments[previousIndex].startedAt else { return nil }
            }

            if let nextIndex {
                guard endedAt < segments[nextIndex].endedAt else { return nil }
            }

            let points = try fetchPoints(trackID: segment.trackID, db: db)
            guard segmentPoints(points, from: startedAt, to: endedAt).count > 1 else {
                return nil
            }

            let now = SyncableTimestamp.nowMilliseconds()
            var changedSegments: [TrackSegment] = []

            if let previousIndex, startedAt != originalStartedAt {
                var previous = segments[previousIndex]
                guard segmentPoints(points, from: previous.startedAt, to: startedAt).count > 1 else {
                    return nil
                }
                previous = makeSegment(
                    basedOn: previous,
                    startedAt: previous.startedAt,
                    endedAt: startedAt,
                    points: points,
                    updatedAt: now
                )
                changedSegments.append(previous)
            }

            segment = makeSegment(
                basedOn: segment,
                startedAt: startedAt,
                endedAt: endedAt,
                points: points,
                updatedAt: now
            )
            changedSegments.append(segment)

            if let nextIndex, endedAt != originalEndedAt {
                var next = segments[nextIndex]
                guard segmentPoints(points, from: endedAt, to: next.endedAt).count > 1 else {
                    return nil
                }
                next = makeSegment(
                    basedOn: next,
                    startedAt: endedAt,
                    endedAt: next.endedAt,
                    points: points,
                    updatedAt: now
                )
                changedSegments.append(next)
            }

            for changedSegment in changedSegments {
                try changedSegment.update(db)
                try appendSegmentChange(db: db, segment: changedSegment, operation: .update)
            }

            return segment
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

        let segments = try TrackSegment
            .filter(Column("track_id") == id)
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)

        for var segment in segments {
            segment.deletedAt = deletedAt
            segment.updatedAt = deletedAt
            segment.syncStatus = .pending

            try segment.update(db)
            try changeLogRepository.append(
                db: db,
                table: TrackSegment.databaseTableName,
                entityID: segment.id,
                operation: .delete,
                payload: segment,
                clientTimestampMilliseconds: segment.updatedAt
            )
        }
    }

    private func fetchSegments(trackIDForSegmentID segmentID: String, db: Database) throws -> [TrackSegment] {
        guard let segment = try TrackSegment.fetchOne(db, key: segmentID), segment.deletedAt == nil else {
            return []
        }

        return try TrackSegment
            .filter(Column("track_id") == segment.trackID)
            .filter(Column("deleted_at") == nil)
            .order(Column("started_at").asc, Column("id").asc)
            .fetchAll(db)
    }

    private func fetchPoints(trackID: String, db: Database) throws -> [TrackPoint] {
        try TrackPoint
            .filter(Column("track_id") == trackID)
            .filter(Column("deleted_at") == nil)
            .order(Column("timestamp_ms").asc)
            .fetchAll(db)
    }

    private func makeSegment(
        basedOn segment: TrackSegment,
        startedAt: Int64,
        endedAt: Int64,
        points: [TrackPoint],
        updatedAt: Int64
    ) -> TrackSegment {
        let metrics = segmentMetrics(points: points, from: startedAt, to: endedAt)
        var updatedSegment = segment
        updatedSegment.startedAt = startedAt
        updatedSegment.endedAt = endedAt
        updatedSegment.distanceMeters = metrics.distanceMeters
        updatedSegment.durationSeconds = max(0, (endedAt - startedAt) / 1000)
        updatedSegment.averageSpeedMetersPerSecond = metrics.averageSpeedMetersPerSecond
        updatedSegment.maxSpeedMetersPerSecond = metrics.maxSpeedMetersPerSecond
        updatedSegment.sourceRawValue = TrackSegmentSource.manual.rawValue
        updatedSegment.confidence = 1
        updatedSegment.updatedAt = updatedAt
        updatedSegment.syncStatus = .pending
        return updatedSegment
    }

    private func segmentMetrics(
        points: [TrackPoint],
        from startedAt: Int64,
        to endedAt: Int64
    ) -> (
        distanceMeters: Double,
        averageSpeedMetersPerSecond: Double?,
        maxSpeedMetersPerSecond: Double?
    ) {
        let rangePoints = segmentPoints(points, from: startedAt, to: endedAt)
        guard rangePoints.count > 1 else {
            return (0, nil, rangePoints.compactMap(\.speedMetersPerSecond).max())
        }

        let distanceMeters = zip(rangePoints, rangePoints.dropFirst()).reduce(0) { partialResult, pair in
            let start = CLLocation(latitude: pair.0.latitude, longitude: pair.0.longitude)
            let end = CLLocation(latitude: pair.1.latitude, longitude: pair.1.longitude)
            return partialResult + end.distance(from: start)
        }
        let durationSeconds = max(0, (endedAt - startedAt) / 1000)
        let averageSpeed = distanceMeters > 0 && durationSeconds > 0 ? distanceMeters / Double(durationSeconds) : nil
        let maxSpeed = rangePoints.compactMap(\.speedMetersPerSecond).max()
        return (distanceMeters, averageSpeed, maxSpeed)
    }

    private func segmentPoints(
        _ points: [TrackPoint],
        from startedAt: Int64,
        to endedAt: Int64
    ) -> [TrackPoint] {
        points.filter { point in
            point.timestampMilliseconds >= startedAt &&
                point.timestampMilliseconds <= endedAt
        }
    }

    private func appendSegmentChange(
        db: Database,
        segment: TrackSegment,
        operation: ChangeOperation
    ) throws {
        try changeLogRepository.append(
            db: db,
            table: TrackSegment.databaseTableName,
            entityID: segment.id,
            operation: operation,
            payload: segment,
            clientTimestampMilliseconds: segment.updatedAt
        )
    }
}
