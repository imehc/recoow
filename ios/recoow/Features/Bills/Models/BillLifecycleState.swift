import SwiftUI

/// 账单生命周期的展示状态，由结算状态与团购有效期派生而来，仅用于 UI 呈现。
enum BillLifecycleState: Sendable {
    /// 正常账单，无需额外徽标。
    case normal
    /// 团购已核销。
    case redeemed
    /// 团购已过有效期且未核销，可退回。
    case expired
    /// 已退款 / 已退回（终态）。
    case refunded

    var title: String? {
        switch self {
        case .normal:
            nil
        case .redeemed:
            "已核销"
        case .expired:
            "已过期"
        case .refunded:
            "已退款"
        }
    }

    var titleKey: LocalizedStringKey? {
        title.map { LocalizedStringKey($0) }
    }

    var systemImage: String? {
        switch self {
        case .normal:
            nil
        case .redeemed:
            "checkmark.seal.fill"
        case .expired:
            "clock.badge.exclamationmark.fill"
        case .refunded:
            "arrow.uturn.backward.circle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .normal:
            .secondary
        case .redeemed:
            .green
        case .expired:
            .orange
        case .refunded:
            .red
        }
    }
}
