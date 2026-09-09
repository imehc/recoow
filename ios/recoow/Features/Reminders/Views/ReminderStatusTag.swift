import SwiftUI

struct ReminderStatusTag: View {
    let reminder: ReminderRecord

    var body: some View {
        MetadataItemView(title: title, systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(tint)
    }

    private var title: String {
        AppLocalization.string(status.title)
    }

    private var systemImage: String {
        status.systemImage
    }

    private var tint: Color {
        switch status {
        case .completed, .checkedInToday:
            return AppPalette.green
        case .endedEarly, .paused:
            return AppPalette.amber
        case .ready:
            return AppPalette.lavender
        case .broken:
            return AppPalette.red
        case .disabled:
            return .secondary
        case .upcoming:
            return AppPalette.blue
        case .ended:
            return AppPalette.amber
        }
    }

    private var status: ReminderCheckInStatus {
        reminder.checkInStatus()
    }
}
