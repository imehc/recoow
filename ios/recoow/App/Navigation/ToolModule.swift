import SwiftUI

struct ToolModule: Identifiable, Hashable {
    let route: ToolRoute

    var id: ToolRoute { route }

    var titleKey: LocalizedStringKey { route.titleKey }
    var subtitleKey: LocalizedStringKey { route.subtitleKey }
    var systemImage: String { route.systemImage }
    var tint: Color { route.tint }

    @ViewBuilder
    func destinationView() -> some View {
        switch route {
        case .locationTracker:
            LocationTrackerView()
        case .decisionMaker:
            DecisionCollectionsView()
        case .itemLocator:
            ItemLocatorView()
        case .reminders:
            RemindersView()
        case .bills:
            BillsView()
        case .diary:
            DiaryView()
        case .anniversaries:
            AnniversariesView()
        }
    }

    func homeState(in context: ToolHomeStateContext) -> ToolHomeState {
        switch route {
        case .locationTracker:
            ToolHomeState(
                isActive: context.isLocationRecording || context.isLocationPaused,
                status: context.isLocationPaused
                    ? ToolHomeStatus(title: AppLocalization.string("已暂停"), systemImage: "pause.circle", tint: .orange)
                    : nil
            )
        case .reminders where context.todayCheckInCount > 0:
            ToolHomeState(
                status: ToolHomeStatus(
                    title: AppLocalization.format("%d 个待打卡", context.todayCheckInCount),
                    systemImage: "checkmark.circle",
                    tint: .purple
                )
            )
        case .anniversaries:
            ToolHomeState(
                status: context.anniversaryStatusTitle.map {
                    ToolHomeStatus(title: $0, systemImage: "calendar", tint: .pink)
                }
            )
        case .decisionMaker, .itemLocator, .bills, .diary, .reminders:
            ToolHomeState()
        }
    }

    func statisticsDates(in snapshot: ToolStatisticsSnapshot) -> [Date] {
        switch route {
        case .locationTracker:
            snapshot.tracks.map { date(milliseconds: $0.startedAt) }
        case .decisionMaker:
            snapshot.decisionRecords.map { date(milliseconds: $0.selectedAt) }
        case .itemLocator:
            snapshot.items.map { date(milliseconds: $0.updatedAt) }
        case .reminders:
            snapshot.reminders.map { date(milliseconds: $0.scheduledAt) }
        case .bills:
            snapshot.bills.map(\.occurredDate)
        case .diary:
            snapshot.diaries.map(\.occurredDate)
        case .anniversaries:
            snapshot.anniversaries.map(\.occurredDate)
        }
    }

    private func date(milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: Double(milliseconds) / 1000)
    }
}

enum ToolRegistry {
    static let modules = ToolRoute.allCases.map(ToolModule.init)
}

struct ToolHomeStateContext {
    let isLocationRecording: Bool
    let isLocationPaused: Bool
    let todayCheckInCount: Int
    let anniversaryStatusTitle: String?
}

struct ToolHomeState {
    var isActive = false
    var status: ToolHomeStatus?
}

struct ToolHomeStatus {
    let title: String
    let systemImage: String?
    let tint: Color
}

struct ToolStatisticsSnapshot {
    let tracks: [Track]
    let decisionRecords: [DecisionChoiceRecord]
    let items: [StoredItem]
    let reminders: [ReminderRecord]
    let bills: [BillRecord]
    let diaries: [DiaryEntry]
    let anniversaries: [AnniversaryRecord]
}
