import Foundation

struct StatisticsBillChartPoint: Identifiable, Hashable {
    let id: String
    let label: String
    let expenseCents: Int64
    let incomeCents: Int64
    let count: Int
}
