import Foundation

struct StatisticsBillIncomeCategoryPoint: Identifiable, Hashable {
    let category: BillIncomeCategory
    let totalCents: Int64
    let count: Int

    var id: String { category.rawValue }
}
