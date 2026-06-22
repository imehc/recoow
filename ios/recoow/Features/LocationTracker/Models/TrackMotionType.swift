import SwiftUI

enum TrackRecordingStatus: String, Codable, CaseIterable, Sendable {
    case recording
    case paused
    case finished

    var title: String {
        switch self {
        case .recording:
            AppLocalization.string("记录中")
        case .paused:
            AppLocalization.string("已暂停")
        case .finished:
            AppLocalization.string("已完成")
        }
    }
}

enum TrackMotionType: String, Codable, CaseIterable, Identifiable, Sendable {
    case unknown
    case stationary
    case walking
    case running
    case cycling
    case transit
    case driving

    var id: String { rawValue }

    var title: String {
        switch self {
        case .unknown:
            AppLocalization.string("未识别")
        case .stationary:
            AppLocalization.string("静止")
        case .walking:
            AppLocalization.string("走路")
        case .running:
            AppLocalization.string("跑步")
        case .cycling:
            AppLocalization.string("骑行")
        case .transit:
            AppLocalization.string("公共交通")
        case .driving:
            AppLocalization.string("开车")
        }
    }

    var systemImage: String {
        switch self {
        case .unknown:
            "questionmark.circle"
        case .stationary:
            "pause.circle"
        case .walking:
            "figure.walk"
        case .running:
            "figure.run"
        case .cycling:
            "bicycle"
        case .transit:
            "bus"
        case .driving:
            "car"
        }
    }

    var color: Color {
        switch self {
        case .unknown:
            .secondary
        case .stationary:
            .gray
        case .walking:
            .green
        case .running:
            .orange
        case .cycling:
            .cyan
        case .transit:
            .purple
        case .driving:
            .blue
        }
    }
}

enum TrackSegmentSource: String, Codable, Sendable {
    case auto
    case manual

    var title: String {
        switch self {
        case .auto:
            AppLocalization.string("自动")
        case .manual:
            AppLocalization.string("手动")
        }
    }
}
