import SwiftUI

struct StatisticsCheckInProgressRow: View {
    let progress: StatisticsCheckInProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(progress.title)
                    .font(.headline)
                    .lineLimit(1)

                Spacer(minLength: 12)

                Text(progressText)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(progress.isCompleted ? .green : .secondary)
            }

            Text(progress.detailText)
                .font(.footnote)
                .foregroundStyle(.secondary)

            if let progressFraction = progress.progressFraction {
                ProgressView(value: progressFraction)
                    .tint(progress.isCompleted ? .green : .blue)
            }
        }
        .padding(.vertical, 4)
    }

    private var progressText: String {
        guard let totalDays = progress.totalDays else {
            return AppLocalization.format("%d 天", progress.completedDays)
        }

        return AppLocalization.format("%d/%d 天", progress.completedDays, totalDays)
    }
}
