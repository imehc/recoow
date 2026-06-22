import Foundation
import GRDB

struct TrackSegment: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "track_segments"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var trackID: String
    var startedAt: Int64
    var endedAt: Int64
    var distanceMeters: Double
    var durationSeconds: Int64
    var averageSpeedMetersPerSecond: Double?
    var maxSpeedMetersPerSecond: Double?
    var motionTypeRawValue: String
    var sourceRawValue: String
    var confidence: Double

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case trackID = "track_id"
        case startedAt = "started_at"
        case endedAt = "ended_at"
        case distanceMeters = "distance_m"
        case durationSeconds = "duration_s"
        case averageSpeedMetersPerSecond = "avg_speed_mps"
        case maxSpeedMetersPerSecond = "max_speed_mps"
        case motionTypeRawValue = "motion_type"
        case sourceRawValue = "source"
        case confidence
    }

    var motionType: TrackMotionType {
        TrackMotionType(rawValue: motionTypeRawValue) ?? .unknown
    }

    var source: TrackSegmentSource {
        TrackSegmentSource(rawValue: sourceRawValue) ?? .auto
    }

    static func make(
        trackID: String,
        startedAt: Int64,
        endedAt: Int64,
        distanceMeters: Double,
        averageSpeedMetersPerSecond: Double?,
        maxSpeedMetersPerSecond: Double?,
        motionType: TrackMotionType,
        source: TrackSegmentSource = .auto,
        confidence: Double,
        deviceID: String
    ) -> TrackSegment {
        let now = SyncableTimestamp.nowMilliseconds()
        return TrackSegment(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            trackID: trackID,
            startedAt: startedAt,
            endedAt: endedAt,
            distanceMeters: distanceMeters,
            durationSeconds: max(0, (endedAt - startedAt) / 1000),
            averageSpeedMetersPerSecond: averageSpeedMetersPerSecond,
            maxSpeedMetersPerSecond: maxSpeedMetersPerSecond,
            motionTypeRawValue: motionType.rawValue,
            sourceRawValue: source.rawValue,
            confidence: confidence
        )
    }
}
