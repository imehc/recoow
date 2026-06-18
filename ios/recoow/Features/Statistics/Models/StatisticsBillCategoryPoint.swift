import Foundation

struct StatisticsBillCategoryPoint: Identifiable, Hashable {
    let category: BillCategory
    let totalCents: Int64
    let count: Int

    var id: String { category.rawValue }
}
