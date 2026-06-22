import AppIntents
import SwiftUI
import WidgetKit

enum WidgetToolRoute: String, CaseIterable, AppEnum, Identifiable {
    case locationTracker
    case decisionMaker
    case itemLocator
    case reminders
    case bills
    case diary
    case anniversaries

    var id: String { rawValue }

    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: "功能项")
    }

    static var caseDisplayRepresentations: [WidgetToolRoute: DisplayRepresentation] {
        [
            .locationTracker: DisplayRepresentation(title: "轨迹记录", image: DisplayRepresentation.Image(systemName: "location.fill")),
            .decisionMaker: DisplayRepresentation(title: "选什么", image: DisplayRepresentation.Image(systemName: "shuffle")),
            .itemLocator: DisplayRepresentation(title: "在哪里", image: DisplayRepresentation.Image(systemName: "shippingbox.fill")),
            .reminders: DisplayRepresentation(title: "打卡任务", image: DisplayRepresentation.Image(systemName: "checkmark.circle.fill")),
            .bills: DisplayRepresentation(title: "记一笔", image: DisplayRepresentation.Image(systemName: "receipt.fill")),
            .diary: DisplayRepresentation(title: "日记", image: DisplayRepresentation.Image(systemName: "book.closed.fill")),
            .anniversaries: DisplayRepresentation(title: "纪念日", image: DisplayRepresentation.Image(systemName: "calendar"))
        ]
    }

    var titleKey: String {
        switch self {
        case .locationTracker:
            "轨迹记录"
        case .decisionMaker:
            "选什么"
        case .itemLocator:
            "在哪里"
        case .reminders:
            "打卡任务"
        case .bills:
            "记一笔"
        case .diary:
            "日记"
        case .anniversaries:
            "纪念日"
        }
    }

    var systemImage: String {
        switch self {
        case .locationTracker:
            "location.fill"
        case .decisionMaker:
            "shuffle"
        case .itemLocator:
            "shippingbox.fill"
        case .reminders:
            "checkmark.circle.fill"
        case .bills:
            "receipt.fill"
        case .diary:
            "book.closed.fill"
        case .anniversaries:
            "calendar"
        }
    }

    var tint: Color {
        switch self {
        case .locationTracker:
            .green
        case .decisionMaker:
            .orange
        case .itemLocator:
            .blue
        case .reminders:
            .purple
        case .bills:
            .teal
        case .diary:
            .mint
        case .anniversaries:
            .pink
        }
    }

    var url: URL {
        URL(string: "recoow://tool/\(rawValue)")!
    }
}

struct QuickAccessConfigurationIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "功能入口"
    static var description = IntentDescription("选择小组件点击后打开的功能。")

    @Parameter(title: "进入页面")
    var selectedTool: WidgetToolRoute?
}

struct QuickAccessEntry: TimelineEntry {
    let date: Date
    let selectedTool: WidgetToolRoute?
}

struct QuickAccessProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> QuickAccessEntry {
        QuickAccessEntry(date: Date(), selectedTool: .locationTracker)
    }

    func snapshot(for configuration: QuickAccessConfigurationIntent, in context: Context) async -> QuickAccessEntry {
        QuickAccessEntry(date: Date(), selectedTool: configuration.selectedTool ?? .locationTracker)
    }

    func timeline(for configuration: QuickAccessConfigurationIntent, in context: Context) async -> Timeline<QuickAccessEntry> {
        Timeline(entries: [QuickAccessEntry(date: Date(), selectedTool: configuration.selectedTool)], policy: .never)
    }
}

struct QuickAccessWidgetEntryView: View {
    let entry: QuickAccessEntry

    var body: some View {
        if let selectedTool = entry.selectedTool {
            Link(destination: selectedTool.url) {
                ToolEntryTile(tool: selectedTool)
            }
            .buttonStyle(.plain)
            .containerBackground(for: .widget) {
                WidgetBackgroundView(tint: selectedTool.tint)
            }
        } else {
            SelectionRequiredTile()
                .containerBackground(for: .widget) {
                    WidgetBackgroundView(tint: .blue)
                }
        }
    }
}

struct ToolEntryTile: View {
    let tool: WidgetToolRoute

    var body: some View {
        VStack(spacing: 10) {
            Spacer(minLength: 0)

            Image(systemName: tool.systemImage)
                .font(.system(size: 42, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 82, height: 82)
                .background(.white.opacity(0.18), in: .rect(cornerRadius: 24, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(.white.opacity(0.22), lineWidth: 1)
                }

            Spacer(minLength: 0)

            Text(LocalizedStringKey(tool.titleKey))
                .font(.headline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

struct SelectionRequiredTile: View {
    var body: some View {
        VStack(spacing: 10) {
            Spacer(minLength: 0)

            Image(systemName: "hand.tap.fill")
                .font(.system(size: 38, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 78, height: 78)
                .background(.white.opacity(0.18), in: .rect(cornerRadius: 24, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(.white.opacity(0.22), lineWidth: 1)
                }

            Spacer(minLength: 0)

            Text("选择入口")
                .font(.headline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.74)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

struct WidgetBackgroundView: View {
    let tint: Color

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    tint.opacity(0.95),
                    Color(red: 0.12, green: 0.16, blue: 0.35)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            RadialGradient(
                colors: [.white.opacity(0.2), .clear],
                center: .topTrailing,
                startRadius: 8,
                endRadius: 118
            )
        }
    }
}

@main
struct RecoowWidget: Widget {
    let kind = "RecoowToolEntryWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: QuickAccessConfigurationIntent.self, provider: QuickAccessProvider()) { entry in
            QuickAccessWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("功能入口")
        .description("选择一个功能，点击小组件直接打开。")
        .supportedFamilies([.systemSmall])
    }
}
