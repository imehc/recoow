import Combine
import SwiftUI

struct HomeView: View {
    @Environment(AppContainer.self) private var container
    @State private var remindersViewModel: RemindersViewModel?
    @State private var anniversariesViewModel: AnniversariesViewModel?
    @State private var currentDate = Date()

    let openSettings: () -> Void

    var body: some View {
        Group {
            if visibleTools.isEmpty, locationTracker.isRecording == false {
                ContentUnavailableView {
                    Label(AppLocalization.string("未显示功能入口"), systemImage: "square.grid.2x2")
                } description: {
                    Text(AppLocalization.string("可在设置中开启"))
                } actions: {
                    Button(AppLocalization.string("打开设置"), systemImage: "slider.horizontal.3", action: openSettings)
                }
            } else {
                List {
                    if locationTracker.isRecording {
                        Section(AppLocalization.string("正在进行")) {
                            NavigationLink(value: ToolRoute.locationTracker) {
                                ActiveFeatureBanner(
                                    route: .locationTracker,
                                    elapsedSeconds: locationTracker.elapsedSeconds,
                                    pointCount: locationTracker.pointCount
                                )
                            }
                        }
                    }

                    Section(AppLocalization.string("工具")) {
                        if visibleTools.isEmpty {
                            ContentUnavailableView {
                                Label(AppLocalization.string("未显示功能入口"), systemImage: "square.grid.2x2")
                            } description: {
                                Text(AppLocalization.string("可在设置中开启"))
                            } actions: {
                                Button(AppLocalization.string("打开设置"), systemImage: "slider.horizontal.3", action: openSettings)
                            }
                        } else {
                            ForEach(visibleModules) { module in
                                let homeState = module.homeState(in: homeStateContext)

                                NavigationLink(value: module.route) {
                                    FeatureEntryTile(
                                        module: module,
                                        isActive: homeState.isActive,
                                        status: homeState.status
                                    )
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle(AppLocalization.string("记刻"))
        .toolbar {
            Button(AppLocalization.string("设置"), systemImage: "slider.horizontal.3", action: openSettings)
        }
        .navigationDestination(for: ToolRoute.self) { route in
            ToolDestinationView(route: route)
        }
        .task {
            if remindersViewModel == nil {
                let model = container.makeRemindersViewModel()
                model.startObserving()
                remindersViewModel = model
            }

            if anniversariesViewModel == nil {
                let model = container.makeAnniversariesViewModel()
                model.startObserving()
                anniversariesViewModel = model
            }
        }
        .onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { date in
            currentDate = date
        }
    }

    private var visibleTools: [ToolRoute] {
        container.featureVisibilitySettings.visibleTools
    }

    private var visibleModules: [ToolModule] {
        visibleTools.map(ToolModule.init)
    }

    private var locationTracker: LocationTrackerViewModel {
        container.locationTrackerViewModel
    }

    private var todayCheckIns: [ReminderRecord] {
        remindersViewModel?.todayCheckIns(on: currentDate) ?? []
    }

    private var homeAnniversaries: [AnniversaryRecord] {
        anniversariesViewModel?.homeAnniversaries(on: currentDate) ?? []
    }

    private var homeStateContext: ToolHomeStateContext {
        ToolHomeStateContext(
            isLocationRecording: locationTracker.isRecording,
            todayCheckInCount: todayCheckIns.count,
            anniversaryStatusTitle: homeAnniversaries.isEmpty ? nil : anniversaryStatusTitle
        )
    }

    private var anniversaryStatusTitle: String {
        guard let first = homeAnniversaries.first,
              let days = first.daysUntilNext(from: currentDate) else {
            return homeAnniversaries.count == 1
                ? AppLocalization.string("近期")
                : AppLocalization.format("%d 个近期", homeAnniversaries.count)
        }

        if days == 0 {
            return homeAnniversaries.count == 1
                ? AppLocalization.string("今天")
                : AppLocalization.format("%d 个今日", homeAnniversaries.count)
        }

        return AppLocalization.format("%d 天后", days)
    }
}
