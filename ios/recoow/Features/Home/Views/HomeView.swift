import SwiftUI

struct HomeView: View {
    @Environment(AppContainer.self) private var container

    let openSettings: () -> Void

    var body: some View {
        Group {
            if visibleTools.isEmpty, locationTracker.isRecording == false {
                ContentUnavailableView {
                    Label("未显示功能入口", systemImage: "square.grid.2x2")
                } description: {
                    Text("可在设置中开启")
                } actions: {
                    Button("打开设置", systemImage: "slider.horizontal.3", action: openSettings)
                }
            } else {
                List {
                    if locationTracker.isRecording {
                        Section("正在进行") {
                            NavigationLink(value: ToolRoute.locationTracker) {
                                ActiveFeatureBanner(
                                    route: .locationTracker,
                                    elapsedSeconds: locationTracker.elapsedSeconds,
                                    pointCount: locationTracker.pointCount
                                )
                            }
                        }
                    }

                    Section("工具") {
                        if visibleTools.isEmpty {
                            ContentUnavailableView {
                                Label("未显示功能入口", systemImage: "square.grid.2x2")
                            } description: {
                                Text("可在设置中开启")
                            } actions: {
                                Button("打开设置", systemImage: "slider.horizontal.3", action: openSettings)
                            }
                        } else {
                            ForEach(visibleTools) { route in
                                NavigationLink(value: route) {
                                    FeatureEntryTile(route: route, isActive: isActive(route))
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("记刻")
        .toolbar {
            Button("设置", systemImage: "slider.horizontal.3", action: openSettings)
        }
        .navigationDestination(for: ToolRoute.self) { route in
            ToolDestinationView(route: route)
        }
    }

    private var visibleTools: [ToolRoute] {
        container.featureVisibilitySettings.visibleTools
    }

    private var locationTracker: LocationTrackerViewModel {
        container.locationTrackerViewModel
    }

    private func isActive(_ route: ToolRoute) -> Bool {
        switch route {
        case .locationTracker:
            locationTracker.isRecording
        }
    }
}
