@preconcurrency import CoreLocation
import Foundation

/// CoreLocation 权限封装。轨迹记录可升级到始终允许，日记取点只请求使用期间。
final class LocationAuthorization: NSObject, @unchecked Sendable, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLAuthorizationStatus, Never>?
    private var shouldUpgradeToAlways = false

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
            shouldUpgradeToAlways = true
            manager.requestAlwaysAuthorization()
        case .notDetermined:
            shouldUpgradeToAlways = true
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
    func requestWhenInUseAuthorization() async -> CLAuthorizationStatus {
        manager.delegate = self
        let status = manager.authorizationStatus

        switch status {
        case .authorizedAlways, .authorizedWhenInUse:
            return status
        case .notDetermined:
            shouldUpgradeToAlways = false
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

        if status == .authorizedWhenInUse, shouldUpgradeToAlways {
            shouldUpgradeToAlways = false
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
