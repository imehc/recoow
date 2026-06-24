import SwiftUI

enum FoodMealKind: String, CaseIterable, Identifiable, Codable, Sendable {
    case breakfast
    case lunch
    case dinner
    case lateNightSnack
    case snack
    case drink
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .breakfast:
            "早餐"
        case .lunch:
            "午餐"
        case .dinner:
            "晚餐"
        case .lateNightSnack:
            "夜宵"
        case .snack:
            "零食"
        case .drink:
            "饮品"
        case .other:
            "其他"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    var localizedTitle: String {
        AppLocalization.string(title)
    }

    var systemImage: String {
        switch self {
        case .breakfast:
            "sunrise.fill"
        case .lunch:
            "fork.knife"
        case .dinner:
            "moon.stars.fill"
        case .lateNightSnack:
            "takeoutbag.and.cup.and.straw.fill"
        case .snack:
            "birthday.cake.fill"
        case .drink:
            "cup.and.saucer.fill"
        case .other:
            "ellipsis.circle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .breakfast:
            .orange
        case .lunch:
            .green
        case .dinner:
            .indigo
        case .lateNightSnack:
            .purple
        case .snack:
            .pink
        case .drink:
            .cyan
        case .other:
            .secondary
        }
    }
}
