import Foundation
import SwiftUI

enum BillIncomeCategory: String, CaseIterable, Identifiable, Codable, Sendable {
    case salary
    case bonus
    case investmentDividend
    case appPayout
    case partTime
    case reimbursement
    case refund
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .salary:
            "工资"
        case .bonus:
            "奖金"
        case .investmentDividend:
            "理财分红"
        case .appPayout:
            "App活动"
        case .partTime:
            "兼职副业"
        case .reimbursement:
            "报销"
        case .refund:
            "退款"
        case .other:
            "其他收入"
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
        case .salary:
            "briefcase.fill"
        case .bonus:
            "giftcard.fill"
        case .investmentDividend:
            "chart.line.uptrend.xyaxis"
        case .appPayout:
            "apps.iphone"
        case .partTime:
            "clock.badge.checkmark.fill"
        case .reimbursement:
            "doc.text.fill"
        case .refund:
            "arrow.uturn.backward.circle.fill"
        case .other:
            "ellipsis.circle.fill"
        }
    }
}
