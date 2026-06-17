import CoreLocation

/// 用户可选择的定位精度。distanceFilter 用于本地过滤，减少无意义写库。
enum LocationAccuracy: Int, CaseIterable, Identifiable, Codable, Sendable {
    case hundredMeters = 100
    case tenMeters = 10
    case fiveMeters = 5

    nonisolated var id: Int { rawValue }

    nonisolated var title: String {
        switch self {
        case .hundredMeters:
            "100m"
        case .tenMeters:
            "10m"
        case .fiveMeters:
            "5m"
        }
    }

    nonisolated var desiredAccuracy: CLLocationAccuracy {
        switch self {
        case .hundredMeters:
            kCLLocationAccuracyHundredMeters
        case .tenMeters:
            kCLLocationAccuracyNearestTenMeters
        case .fiveMeters:
            kCLLocationAccuracyBest
        }
    }

    nonisolated var distanceFilter: CLLocationDistance {
        switch self {
        case .hundredMeters:
            100
        case .tenMeters:
            10
        case .fiveMeters:
            5
        }
    }

    nonisolated var liveConfiguration: CLLocationUpdate.LiveConfiguration {
        switch self {
        case .hundredMeters:
            .default
        case .tenMeters, .fiveMeters:
            .fitness
        }
    }
}
