import Foundation
import SwiftUI

enum BillCategory: String, CaseIterable, Identifiable, Codable, Sendable {
    case dining
    case shopping
    case transport
    case housing
    case entertainment
    case medical
    case learning
    case social
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dining:
            "餐饮"
        case .shopping:
            "购物"
        case .transport:
            "交通"
        case .housing:
            "住房"
        case .entertainment:
            "娱乐"
        case .medical:
            "医疗"
        case .learning:
            "学习"
        case .social:
            "人情"
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
        case .dining:
            "fork.knife"
        case .shopping:
            "bag.fill"
        case .transport:
            "car.fill"
        case .housing:
            "house.fill"
        case .entertainment:
            "gamecontroller.fill"
        case .medical:
            "cross.case.fill"
        case .learning:
            "book.fill"
        case .social:
            "gift.fill"
        case .other:
            "ellipsis.circle.fill"
        }
    }
}
