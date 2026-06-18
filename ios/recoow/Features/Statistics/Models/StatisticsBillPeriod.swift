import Foundation

enum StatisticsBillPeriod: String, CaseIterable, Identifiable {
    case week
    case month
    case year

    var id: String { rawValue }

    var title: String {
        switch self {
        case .week:
            "周"
        case .month:
            "月"
        case .year:
            "年"
        }
    }

    var historyFilterTitleKey: String {
        switch self {
        case .week:
            "本周账单"
        case .month:
            "本月账单"
        case .year:
            "本年账单"
        }
    }
}
