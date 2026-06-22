import Foundation
import SwiftUI

enum DiaryMood: String, CaseIterable, Identifiable, Codable, Sendable {
    case neutral
    case happy
    case calm
    case tired
    case stressed
    case sad

    var id: String { rawValue }

    var title: String {
        switch self {
        case .neutral:
            "平常"
        case .happy:
            "开心"
        case .calm:
            "平静"
        case .tired:
            "疲惫"
        case .stressed:
            "有压力"
        case .sad:
            "低落"
        }
    }

    var systemImage: String {
        switch self {
        case .neutral:
            "circle"
        case .happy:
            "sun.max.fill"
        case .calm:
            "leaf.fill"
        case .tired:
            "moon.zzz.fill"
        case .stressed:
            "bolt.fill"
        case .sad:
            "cloud.rain.fill"
        }
    }

    var tint: Color {
        switch self {
        case .neutral:
            .secondary
        case .happy:
            .yellow
        case .calm:
            .green
        case .tired:
            .indigo
        case .stressed:
            .orange
        case .sad:
            .blue
        }
    }
}
