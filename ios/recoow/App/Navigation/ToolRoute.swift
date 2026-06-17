import SwiftUI

enum ToolRoute: String, CaseIterable, Identifiable, Hashable {
    case locationTracker

    var id: String { rawValue }

    var title: String {
        switch self {
        case .locationTracker:
            "轨迹记录"
        }
    }

    var subtitle: String {
        switch self {
        case .locationTracker:
            "记录路线、距离与速度"
        }
    }

    var systemImage: String {
        switch self {
        case .locationTracker:
            "location.fill"
        }
    }

    var tint: Color {
        switch self {
        case .locationTracker:
            .green
        }
    }
}
