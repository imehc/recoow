import SwiftUI

enum AnniversaryCategory: String, CaseIterable, Identifiable, Codable, Sendable {
    case birthday
    case relationship
    case family
    case workStudy
    case personalGoal
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .birthday:
            "生日"
        case .relationship:
            "恋爱/婚姻"
        case .family:
            "家庭"
        case .workStudy:
            "工作/学习"
        case .personalGoal:
            "个人目标"
        case .other:
            "其他"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    var systemImage: String {
        switch self {
        case .birthday:
            "birthday.cake.fill"
        case .relationship:
            "heart.fill"
        case .family:
            "house.fill"
        case .workStudy:
            "briefcase.fill"
        case .personalGoal:
            "target"
        case .other:
            "sparkles"
        }
    }

    var tint: Color {
        switch self {
        case .birthday:
            .pink
        case .relationship:
            .red
        case .family:
            .blue
        case .workStudy:
            .indigo
        case .personalGoal:
            .orange
        case .other:
            .teal
        }
    }
}
