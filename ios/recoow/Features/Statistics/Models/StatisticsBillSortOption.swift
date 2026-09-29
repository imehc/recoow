import SwiftUI

/// 统计账单详情页的排序方式。
enum StatisticsBillSortOption: String, CaseIterable, Identifiable {
    case date
    case amountDescending
    case amountAscending

    var id: String { rawValue }

    var title: String {
        switch self {
        case .date:
            "按时间"
        case .amountDescending:
            "金额从大到小"
        case .amountAscending:
            "金额从小到大"
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
        case .date:
            "clock"
        case .amountDescending:
            "arrow.down.circle"
        case .amountAscending:
            "arrow.up.circle"
        }
    }

    /// 金额档以发生时间倒序兜底，避免等金额的行在每次刷新时抖动。
    func applying(to bills: [BillRecord], amount: (BillRecord) -> Int64) -> [BillRecord] {
        guard self != .date else { return bills }

        let amountAscending = self == .amountAscending

        return bills.sorted { lhs, rhs in
            let lhsAmount = amount(lhs)
            let rhsAmount = amount(rhs)
            guard lhsAmount != rhsAmount else {
                return lhs.occurredAt > rhs.occurredAt
            }

            return amountAscending ? lhsAmount < rhsAmount : lhsAmount > rhsAmount
        }
    }
}
