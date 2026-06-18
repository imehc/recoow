import Charts
import SwiftUI

struct StatisticsRecentUsageChartSection: View {
    let totalRecordCount: Int
    let points: [StatisticsUsageChartPoint]

    var body: some View {
        Section("历史趋势") {
            if totalRecordCount == 0 {
                ContentUnavailableView("暂无统计数据", systemImage: "chart.bar")
            } else {
                Chart(points) { point in
                    BarMark(
                        x: .value(AppLocalization.string("日期"), point.label),
                        y: .value(AppLocalization.string("记录数"), point.count)
                    )
                    .foregroundStyle(.blue.gradient)
                    .clipShape(.rect(cornerRadius: 4))
                }
                .frame(height: 160)
                .chartYAxis {
                    AxisMarks(position: .leading)
                }
                .accessibilityLabel(AppLocalization.string("最近7天"))
            }
        }
    }
}
