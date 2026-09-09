import SwiftUI

struct StatisticsCheckInProgressRow: View {
    let progress: StatisticsCheckInProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(progress.title)
                    .font(AppDesign.rowTitleFont)
                    .foregroundStyle(AppPalette.ink)
                    .lineLimit(1)

                Spacer(minLength: 12)

                Text(progressText)
                    .font(AppDesign.metadataFont.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(progress.isCompleted ? AppPalette.green : AppPalette.secondaryInk)
            }

            Text(progress.detailText)
                .font(AppDesign.metadataFont)
                .foregroundStyle(AppPalette.secondaryInk)

            if let progressFraction = progress.progressFraction {
                ProgressView(value: progressFraction)
                    .tint(progress.isCompleted ? AppPalette.green : AppPalette.accent)
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
