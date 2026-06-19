import Foundation
import SwiftUI

enum BillType: String, CaseIterable, Identifiable, Codable, Sendable {
    case expense
    case income

    var id: String { rawValue }

    var title: String {
        switch self {
        case .expense:
            "支出"
        case .income:
            "收入"
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
        }
    }

    var amountTint: Color {
        switch self {
        case .expense:
            .red
        case .income:
            .green
        }
    }
}
