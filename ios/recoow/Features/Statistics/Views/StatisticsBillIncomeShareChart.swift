import Charts
import SwiftUI

struct StatisticsBillIncomeShareChart: View {
    let points: [StatisticsBillIncomeCategoryPoint]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(AppLocalization.string("收入分类占比"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)

            Chart(points) { point in
                SectorMark(
                    angle: .value(AppLocalization.string("金额"), Double(point.totalCents) / 100),
                    innerRadius: .ratio(0.6),
                    angularInset: 1.5
                )
                .foregroundStyle(by: .value(AppLocalization.string("分类"), point.category.localizedTitle))
            }
            .frame(height: 164)
            .chartLegend(position: .bottom, alignment: .center, spacing: 6)
            .accessibilityLabel(AppLocalization.string("收入分类占比"))
        }
    }
}
