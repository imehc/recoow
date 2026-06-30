import CoreLocation
import OSLog

enum LocationServiceError: LocalizedError {
    case authorizationDenied
    case timedOut
    case unavailable

    var errorDescription: String? {
        switch self {
        case .authorizationDenied:
            "定位权限未开启"
        case .timedOut:
            "获取当前位置超时"
        case .unavailable:
            "暂时无法获取当前位置"
        }
    }
}

/// 定位服务 actor。对外暴露异步流，内部持有后台定位 session 生命周期。
actor LocationService {
    private let authorization = LocationAuthorization()
    private var backgroundSession: CLBackgroundActivitySession?
    private var serviceSession: AnyObject?

    func requestAuthorization() async -> CLAuthorizationStatus {
        await authorization.requestAlwaysAuthorization()
    }

    func requestTemporaryAuthorization() async -> CLAuthorizationStatus {
        await authorization.requestWhenInUseAuthorization()
    }

    func currentLocation(accuracy: LocationAccuracy) async throws -> CLLocation {
        let authorization = await requestTemporaryAuthorization()

        guard authorization == .authorizedAlways || authorization == .authorizedWhenInUse else {
            throw LocationServiceError.authorizationDenied
        }

        return try await withThrowingTaskGroup(of: CLLocation.self) { group in
            group.addTask {
                for try await update in CLLocationUpdate.liveUpdates(accuracy.liveConfiguration) {
                    guard let location = update.location else { continue }
                    let requiredAccuracy = max(accuracy.desiredAccuracy, accuracy.distanceFilter * 2)
                    if location.horizontalAccuracy >= 0,
                       location.horizontalAccuracy <= requiredAccuracy {
                        return location
                    }
                }

                throw LocationServiceError.unavailable
            }

            group.addTask {
                try await Task.sleep(for: .seconds(12))
                throw LocationServiceError.timedOut
            }

            guard let location = try await group.next() else {
                throw LocationServiceError.unavailable
            }

            group.cancelAll()
            return location
        }
    }

    func liveUpdates(accuracy: LocationAccuracy) -> AsyncStream<CLLocationUpdate> {
        stop()
        backgroundSession = CLBackgroundActivitySession()

        if #available(iOS 18.0, *) {
            serviceSession = CLServiceSession(authorization: .always)
        }

        return AsyncStream { continuation in
            let task = Task {
                var lastAcceptedLocation: CLLocation?
                var lastAcceptedAt: Date?

                do {
                    for try await update in CLLocationUpdate.liveUpdates(accuracy.liveConfiguration) {
                        guard Task.isCancelled == false else { break }

                        guard let location = update.location else {
                            continuation.yield(update)
                            continue
                        }

                        let isAccurateEnough = location.horizontalAccuracy <= max(accuracy.desiredAccuracy, accuracy.distanceFilter * 2)
                        let movedEnough = lastAcceptedLocation.map { location.distance(from: $0) >= accuracy.distanceFilter } ?? true
                        let stationaryHeartbeat = location.speed >= 0 &&
                            location.speed < 0.5 &&
                            (lastAcceptedAt.map { location.timestamp.timeIntervalSince($0) >= 30 } ?? true)

                        if isAccurateEnough, movedEnough || stationaryHeartbeat {
                            lastAcceptedLocation = location
                            lastAcceptedAt = location.timestamp
                            continuation.yield(update)
                        }
                    }
                    continuation.finish()
                } catch {
                    AppLogger.location.error("定位更新流结束: \(error.localizedDescription, privacy: .public)")
                    continuation.finish()
                }
            }

            continuation.onTermination = { @Sendable _ in
                task.cancel()
                Task {
                    await self.stop()
                }
            }
        }
    }

    func stop() {
        backgroundSession?.invalidate()
        backgroundSession = nil

        if #available(iOS 18.0, *), let serviceSession = serviceSession as? CLServiceSession {
            serviceSession.invalidate()
        }
        serviceSession = nil
    }
}
