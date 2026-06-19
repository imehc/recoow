import Combine
import SwiftUI

struct HomeView: View {
    @Environment(AppContainer.self) private var container
    @State private var remindersViewModel: RemindersViewModel?
    @State private var currentDate = Date()

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
                                    FeatureEntryTile(
                                        route: route,
                                        isActive: isActive(route),
                                        statusTitle: statusTitle(for: route),
                                        statusSystemImage: statusSystemImage(for: route),
                                        statusTint: statusTint(for: route)
                                    )
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
        .task {
            guard remindersViewModel == nil else { return }

            let model = RemindersViewModel(
                repository: container.reminderRepository,
                notificationService: container.reminderNotificationService,
                syncEngine: container.syncEngine
            )
            model.startObserving()
            remindersViewModel = model
        }
        .onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { date in
            currentDate = date
        }
    }

    private var visibleTools: [ToolRoute] {
        container.featureVisibilitySettings.visibleTools
    }

    private var locationTracker: LocationTrackerViewModel {
        container.locationTrackerViewModel
    }

    private var todayCheckIns: [ReminderRecord] {
        remindersViewModel?.todayCheckIns(on: currentDate) ?? []
    }

    private func isActive(_ route: ToolRoute) -> Bool {
        switch route {
        case .locationTracker:
            locationTracker.isRecording
        case .decisionMaker, .itemLocator, .reminders, .bills:
            false
        }
    }

    private func statusTitle(for route: ToolRoute) -> String? {
        switch route {
        case .reminders where todayCheckIns.isEmpty == false:
            "\(todayCheckIns.count) 个待打卡"
        default:
            nil
        }
    }

    private func statusSystemImage(for route: ToolRoute) -> String? {
        switch route {
        case .reminders where todayCheckIns.isEmpty == false:
            "checkmark.circle"
        default:
            nil
        }
    }

    private func statusTint(for route: ToolRoute) -> Color {
        switch route {
        case .reminders where todayCheckIns.isEmpty == false:
            .purple
        default:
            .green
        }
    }
}
