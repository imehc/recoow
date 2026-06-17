@preconcurrency import CoreLocation
import Foundation

/// CoreLocation 权限升级封装：先请求使用期间，再尝试升级始终允许。
final class LocationAuthorization: NSObject, @unchecked Sendable, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLAuthorizationStatus, Never>?
    private var didRequestAlways = false

    nonisolated override init() {
        super.init()
    }

    @MainActor
    func requestAlwaysAuthorization() async -> CLAuthorizationStatus {
        manager.delegate = self
        let status = manager.authorizationStatus

        switch status {
        case .authorizedAlways:
            return status
        case .authorizedWhenInUse:
            didRequestAlways = true
            manager.requestAlwaysAuthorization()
        case .notDetermined:
            didRequestAlways = false
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            return status
        @unknown default:
            return status
        }

        return await withCheckedContinuation { continuation in
            self.continuation = continuation
        }
    }

    @MainActor
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus

        if status == .authorizedWhenInUse, didRequestAlways == false {
            didRequestAlways = true
            manager.requestAlwaysAuthorization()
            return
        }

        guard let continuation else { return }

        switch status {
        case .authorizedAlways, .authorizedWhenInUse, .denied, .restricted:
            self.continuation = nil
            continuation.resume(returning: status)
        case .notDetermined:
            break
        @unknown default:
            self.continuation = nil
            continuation.resume(returning: status)
        }
    }
}
