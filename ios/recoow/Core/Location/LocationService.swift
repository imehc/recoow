import CoreLocation
import OSLog

/// 定位服务 actor。对外暴露异步流，内部持有后台定位 session 生命周期。
actor LocationService {
    private let authorization = LocationAuthorization()
    private var backgroundSession: CLBackgroundActivitySession?
    private var serviceSession: AnyObject?

    func requestAuthorization() async -> CLAuthorizationStatus {
        await authorization.requestAlwaysAuthorization()
    }

    func liveUpdates(accuracy: LocationAccuracy) -> AsyncStream<CLLocationUpdate> {
        backgroundSession = CLBackgroundActivitySession()

        if #available(iOS 18.0, *) {
            serviceSession = CLServiceSession(authorization: .always)
        }

        return AsyncStream { continuation in
            let task = Task {
                var lastAcceptedLocation: CLLocation?

                do {
                    for try await update in CLLocationUpdate.liveUpdates(accuracy.liveConfiguration) {
                        guard Task.isCancelled == false else { break }

                        guard let location = update.location else {
                            continuation.yield(update)
                            continue
                        }

                        let isAccurateEnough = location.horizontalAccuracy <= max(accuracy.desiredAccuracy, accuracy.distanceFilter * 2)
                        let movedEnough = lastAcceptedLocation.map { location.distance(from: $0) >= accuracy.distanceFilter } ?? true

                        if isAccurateEnough, movedEnough {
                            lastAcceptedLocation = location
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
