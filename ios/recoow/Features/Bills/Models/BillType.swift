import Foundation
import SwiftUI

enum BillType: String, CaseIterable, Identifiable, Codable, Sendable {
    case expense
    case income
    case storedValueUse

    var id: String { rawValue }

    var title: String {
        switch self {
        case .expense:
            "支出"
        case .income:
            "收入"
        case .storedValueUse:
            "储值消费"
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
        case .expense:
            "arrow.up.right.circle.fill"
        case .income:
            "arrow.down.left.circle.fill"
        case .storedValueUse:
            "wallet.pass.fill"
        }
    }

    var amountTint: Color {
        switch self {
        case .expense:
            .red
        case .income:
            .green
        case .storedValueUse:
            .orange
        }
    }
}
