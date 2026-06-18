import SwiftUI

struct StatisticsFeatureUsageSection: View {
    let summaries: [StatisticsFeatureSummary]

    var body: some View {
        Section("各板块") {
            ForEach(summaries) { summary in
                StatisticsFeatureUsageRow(summary: summary)
            }
        }
    }
}
