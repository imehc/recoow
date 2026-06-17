import CoreLocation
import Foundation
import GRDB

/// 单个定位采样点。track_points 采用 append-only 模型，便于后续增量同步。
struct TrackPoint: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "track_points"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var trackID: String
    var latitude: Double
    var longitude: Double
    var altitude: Double?
    var horizontalAccuracy: Double?
    var verticalAccuracy: Double?
    var speedMetersPerSecond: Double?
    var courseDegrees: Double?
    var timestampMilliseconds: Int64

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case trackID = "track_id"
        case latitude
        case longitude
        case altitude
        case horizontalAccuracy = "horizontal_acc"
        case verticalAccuracy = "vertical_acc"
        case speedMetersPerSecond = "speed_mps"
        case courseDegrees = "course_deg"
        case timestampMilliseconds = "timestamp_ms"
    }

    static func make(trackID: String, location: CLLocation, deviceID: String) -> TrackPoint {
        let timestamp = Int64(location.timestamp.timeIntervalSince1970 * 1000)
        let speed = location.speed >= 0 ? location.speed : nil
        let course = location.course >= 0 ? location.course : nil

        return TrackPoint(
            id: UUID().uuidString,
            createdAt: timestamp,
            updatedAt: timestamp,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            trackID: trackID,
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            altitude: location.verticalAccuracy >= 0 ? location.altitude : nil,
            horizontalAccuracy: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
            verticalAccuracy: location.verticalAccuracy >= 0 ? location.verticalAccuracy : nil,
            speedMetersPerSecond: speed,
            courseDegrees: course,
            timestampMilliseconds: timestamp
        )
    }
}
