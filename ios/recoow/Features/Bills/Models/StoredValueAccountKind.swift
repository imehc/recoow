import Foundation
import SwiftUI

enum StoredValueAccountKind: String, CaseIterable, Identifiable, Codable, Sendable {
    case transitCard
    case mealCard
    case membershipCard
    case giftCard
    case phoneBalance
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .transitCard:
            "交通卡"
        case .mealCard:
            "饭卡"
        case .membershipCard:
            "会员储值卡"
        case .giftCard:
            "礼品卡"
        case .phoneBalance:
            "话费余额"
        case .other:
            "其他储值"
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
        case .transitCard:
            "tram.fill"
        case .mealCard:
            "fork.knife.circle.fill"
        case .membershipCard:
            "wallet.pass.fill"
        case .giftCard:
            "giftcard.fill"
        case .phoneBalance:
            "phone.fill"
        case .other:
            "creditcard.fill"
        }
    }
}
