import SwiftUI

/// 应用根导航。后续新增工具时，只需要在 ToolRoute 和 tools 中追加入口。
struct AppRoot: View {
    @Environment(AppContainer.self) private var container

    private let tools: [ToolRoute] = [.locationTracker]

    var body: some View {
        TabView {
            Tab("工具", systemImage: "wrench.and.screwdriver") {
                NavigationStack {
                    List(tools) { tool in
                        NavigationLink(value: tool) {
                            Label(tool.title, systemImage: tool.systemImage)
                        }
                    }
                    .navigationTitle("工具")
                    .navigationDestination(for: ToolRoute.self) { tool in
                        switch tool {
                        case .locationTracker:
                            LocationTrackerView()
                        }
                    }
                }
            }

            Tab("历史", systemImage: "clock") {
                NavigationStack {
                    TrackHistoryView()
                }
            }
        }
    }
}

private enum ToolRoute: String, CaseIterable, Identifiable, Hashable {
    case locationTracker

    var id: String { rawValue }

    var title: String {
        switch self {
        case .locationTracker:
            "轨迹记录"
        }
    }

    var systemImage: String {
        switch self {
        case .locationTracker:
            "location.fill"
        }
    }
}

#Preview {
    AppRoot()
        .environment(AppContainer.preview)
}
