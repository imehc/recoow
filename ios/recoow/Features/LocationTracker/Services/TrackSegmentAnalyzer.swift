import CoreLocation
import Foundation

enum TrackSegmentAnalyzer {
    private static let pauseGapMilliseconds: Int64 = 120_000
    private static let maxUsableHorizontalAccuracy = 100.0
    private static let maxReasonableSpeed = 80.0
    private static let smoothingRadius = 2
    private static let shortSegmentSeconds: Int64 = 15

    static func segments(for points: [TrackPoint], trackID: String, deviceID: String) -> [TrackSegment] {
        let legs = movementLegs(for: points)
        guard legs.isEmpty == false else { return [] }

        var builders: [SegmentBuilder] = []

        for index in legs.indices {
            let leg = legs[index]
            let speed = smoothedSpeed(for: legs, at: index)
            let motionType = motionType(for: speed, distance: leg.distanceMeters, deltaSeconds: leg.deltaSeconds)

            if var last = builders.last,
               last.motionType == motionType,
               leg.startedAt - last.endedAt <= pauseGapMilliseconds {
                last.append(
                    endedAt: leg.endedAt,
                    distanceMeters: leg.distanceMeters,
                    speedMetersPerSecond: speed
                )
                builders[builders.index(before: builders.endIndex)] = last
            } else {
                builders.append(
                    SegmentBuilder(
                        startedAt: leg.startedAt,
                        endedAt: leg.endedAt,
                        motionType: motionType,
                        distanceMeters: leg.distanceMeters,
                        speedMetersPerSecond: speed
                    )
                )
            }
        }

        return mergeShortSegments(builders).map { builder in
            TrackSegment.make(
                trackID: trackID,
                startedAt: builder.startedAt,
                endedAt: builder.endedAt,
                distanceMeters: builder.distanceMeters,
                averageSpeedMetersPerSecond: builder.averageSpeedMetersPerSecond,
                maxSpeedMetersPerSecond: builder.maxSpeedMetersPerSecond,
                motionType: builder.motionType,
                confidence: confidence(for: builder.motionType, averageSpeed: builder.averageSpeedMetersPerSecond),
                deviceID: deviceID
            )
        }
    }

    static func metrics(for points: [TrackPoint]) -> (
        distanceMeters: Double,
        durationSeconds: Int64,
        averageSpeedMetersPerSecond: Double?,
        maxSpeedMetersPerSecond: Double?
    ) {
        let segments = self.segments(for: points, trackID: "", deviceID: "")
        let distance = segments.reduce(0) { $0 + $1.distanceMeters }
        let duration = segments.reduce(0) { $0 + $1.durationSeconds }
        let maxSpeed = segments.compactMap(\.maxSpeedMetersPerSecond).max()
        let averageSpeed = distance > 0 && duration > 0 ? distance / Double(duration) : nil

        return (distance, duration, averageSpeed, maxSpeed)
    }

    private static func motionType(for speed: Double, distance: Double, deltaSeconds: Double) -> TrackMotionType {
        if distance < 8, deltaSeconds >= 20 {
            return .stationary
        }

        switch speed {
        case ..<0.5:
            return .stationary
        case ..<2.2:
            return .walking
        case ..<4.8:
            return .running
        case ..<8.5:
            return .cycling
        case ..<14:
            return .transit
        default:
            return .driving
        }
    }

    private static func confidence(for motionType: TrackMotionType, averageSpeed: Double?) -> Double {
        guard let averageSpeed else { return 0.2 }

        switch motionType {
        case .stationary:
            return averageSpeed < 0.5 ? 0.85 : 0.45
        case .walking:
            return (0.6...1.8).contains(averageSpeed) ? 0.8 : 0.55
        case .running:
            return (2.4...4.5).contains(averageSpeed) ? 0.75 : 0.5
        case .cycling:
            return (4.0...8.0).contains(averageSpeed) ? 0.65 : 0.45
        case .transit, .driving:
            return 0.45
        case .unknown:
            return 0.2
        }
    }

    private static func movementLegs(for points: [TrackPoint]) -> [MovementLeg] {
        let sortedPoints = points
            .filter { point in
                guard let accuracy = point.horizontalAccuracy else { return true }
                return accuracy <= maxUsableHorizontalAccuracy
            }
            .sorted { $0.timestampMilliseconds < $1.timestampMilliseconds }

        guard sortedPoints.count > 1 else { return [] }

        return zip(sortedPoints, sortedPoints.dropFirst()).compactMap { start, end in
            let deltaMilliseconds = end.timestampMilliseconds - start.timestampMilliseconds
            guard deltaMilliseconds > 0 else { return nil }

            let deltaSeconds = Double(deltaMilliseconds) / 1000
            let distance = distanceMeters(from: start, to: end)
            let calculatedSpeed = distance / deltaSeconds
            let speed = end.speedMetersPerSecond ?? calculatedSpeed

            guard speed <= maxReasonableSpeed else { return nil }

            return MovementLeg(
                startedAt: start.timestampMilliseconds,
                endedAt: end.timestampMilliseconds,
                distanceMeters: distance,
                deltaSeconds: deltaSeconds,
                speedMetersPerSecond: speed
            )
        }
    }

    private static func smoothedSpeed(for legs: [MovementLeg], at index: Int) -> Double {
        let lowerBound = max(legs.startIndex, index - smoothingRadius)
        let upperBound = min(legs.index(before: legs.endIndex), index + smoothingRadius)
        let samples = legs[lowerBound...upperBound]
            .map(\.speedMetersPerSecond)
            .sorted()

        guard samples.isEmpty == false else {
            return legs[index].speedMetersPerSecond
        }

        return samples[samples.count / 2]
    }

    private static func mergeShortSegments(_ builders: [SegmentBuilder]) -> [SegmentBuilder] {
        builders.reduce(into: []) { result, builder in
            if var previous = result.last,
               builder.durationSeconds < shortSegmentSeconds,
               builder.startedAt - previous.endedAt <= pauseGapMilliseconds {
                previous.merge(builder)
                result[result.index(before: result.endIndex)] = previous
            } else {
                result.append(builder)
            }
        }
    }

    private static func distanceMeters(from start: TrackPoint, to end: TrackPoint) -> Double {
        let startLocation = CLLocation(latitude: start.latitude, longitude: start.longitude)
        let endLocation = CLLocation(latitude: end.latitude, longitude: end.longitude)
        return endLocation.distance(from: startLocation)
    }

    private struct MovementLeg {
        let startedAt: Int64
        let endedAt: Int64
        let distanceMeters: Double
        let deltaSeconds: Double
        let speedMetersPerSecond: Double
    }

    private struct SegmentBuilder {
        let startedAt: Int64
        var endedAt: Int64
        let motionType: TrackMotionType
        var distanceMeters: Double
        var speedSamples: [Double]

        init(
            startedAt: Int64,
            endedAt: Int64,
            motionType: TrackMotionType,
            distanceMeters: Double,
            speedMetersPerSecond: Double
        ) {
            self.startedAt = startedAt
            self.endedAt = endedAt
            self.motionType = motionType
            self.distanceMeters = distanceMeters
            self.speedSamples = [speedMetersPerSecond]
        }

        var durationSeconds: Int64 {
            max(0, (endedAt - startedAt) / 1000)
        }

        var averageSpeedMetersPerSecond: Double? {
            guard durationSeconds > 0, distanceMeters > 0 else { return nil }
            return distanceMeters / Double(durationSeconds)
        }

        var maxSpeedMetersPerSecond: Double? {
            speedSamples.max()
        }

        mutating func append(endedAt: Int64, distanceMeters: Double, speedMetersPerSecond: Double) {
            self.endedAt = endedAt
            self.distanceMeters += distanceMeters
            speedSamples.append(speedMetersPerSecond)
        }

        mutating func merge(_ other: SegmentBuilder) {
            endedAt = other.endedAt
            distanceMeters += other.distanceMeters
            speedSamples.append(contentsOf: other.speedSamples)
        }
    }
}
