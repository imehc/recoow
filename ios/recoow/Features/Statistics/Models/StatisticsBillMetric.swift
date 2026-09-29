import SwiftUI

/// 账单统计区的五个指标卡；同时决定详情页列表的筛选口径与「金额」排序字段。
enum StatisticsBillMetric: String, CaseIterable, Identifiable {
    case expense
    case income
    case count
    case discount
    case storedValueUse

    var id: String { rawValue }

    var title: String {
        switch self {
        case .expense:
            "支出"
        case .income:
            "收入"
        case .count:
            "账单数量"
        case .discount:
            "优惠"
        case .storedValueUse:
            "储值消费"
        }
    }

    var localizedTitle: String {
        AppLocalization.string(title)
    }

    var listSectionTitle: String {
        switch self {
        case .expense:
            "支出账单"
        case .income:
            "收入账单"
        case .count:
            "账单"
        case .discount:
            "优惠账单"
        case .storedValueUse:
            "储值消费账单"
        }
    }

    var localizedListSectionTitle: String {
        AppLocalization.string(listSectionTitle)
    }

    var systemImage: String {
        switch self {
        case .expense:
            "arrow.up.right.circle.fill"
        case .income:
            "arrow.down.left.circle.fill"
        case .count:
            "number"
        case .discount:
            "tag.fill"
        case .storedValueUse:
            "wallet.pass.fill"
        }
    }

    var tint: Color {
        switch self {
        case .expense:
            .red
        case .income:
            .green
        case .count:
            .blue
        case .discount:
            .orange
        case .storedValueUse:
            .purple
        }
    }

    /// 列表展示子集；只筛选不排序，传入顺序需已按发生时间倒序。
    func bills(from periodBills: [BillRecord]) -> [BillRecord] {
        switch self {
        case .expense:
            periodBills.filter { $0.billType == .expense }
        case .income:
            periodBills.filter { $0.billType == .income }
        case .count:
            periodBills
        case .discount:
            periodBills.filter(\.hasDiscount)
        case .storedValueUse:
            periodBills.filter { $0.billType == .storedValueUse }
        }
    }

    /// 排序依据与账单行上显示的金额保持一致。
    func amountCents(of bill: BillRecord) -> Int64 {
        switch self {
        case .discount:
            bill.discountAmountCents
        case .expense, .income, .count, .storedValueUse:
            bill.finalAmountCents
        }
    }
}
