import Combine
import SwiftUI

struct HomeView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.locale) private var locale
    @State private var remindersViewModel: RemindersViewModel?
    @State private var anniversariesViewModel: AnniversariesViewModel?
    @State private var currentDate = Date()

    @Binding private var tabBarVisibility: Visibility
    let openSettings: () -> Void

    private static let recordingRoutes: [ToolRoute] = [.diary, .foodJournal, .bills, .locationTracker]

    init(
        tabBarVisibility: Binding<Visibility> = .constant(.visible),
        openSettings: @escaping () -> Void
    ) {
        _tabBarVisibility = tabBarVisibility
        self.openSettings = openSettings
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: HomeStyle.sectionSpacing) {
                if isLocationActive {
                    activeSection
                }

                if visibleTools.isEmpty {
                    emptyState
                } else {
                    toolSection(title: "日常记录", modules: recordingModules, showsDate: true)
                    toolSection(title: "生活工具", modules: utilityModules)
                }
            }
            .padding(.horizontal, HomeStyle.pagePadding)
            .padding(.top, AppDesign.pageTopPadding)
            .padding(.bottom, HomeStyle.sectionSpacing)
            .frame(maxWidth: HomeStyle.contentMaxWidth)
            .frame(maxWidth: .infinity)
        }
        .background(AppPalette.background.ignoresSafeArea())
        .reportsTabBarVisibilityWhenScrolling($tabBarVisibility)
        .navigationTitle(AppLocalization.string("记刻"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(AppPalette.background, for: .navigationBar)
        .toolbar {
            Button(AppLocalization.string("设置"), systemImage: "slider.horizontal.3", action: openSettings)
                .tint(AppPalette.accent)
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

    private var activeSection: some View {
        VStack(alignment: .leading, spacing: HomeStyle.cardSpacing) {
            sectionHeading("正在进行")

            NavigationLink(value: ToolRoute.locationTracker) {
                ActiveFeatureBanner(
                    route: .locationTracker,
                    statusTitle: locationTracker.isPaused ? "已暂停" : "记录中",
                    statusSystemImage: locationTracker.isPaused ? "pause.circle" : "dot.radiowaves.left.and.right",
                    statusTint: locationTracker.isPaused ? AppPalette.amber : AppPalette.green,
                    elapsedSeconds: locationTracker.elapsedSeconds,
                    pointCount: locationTracker.pointCount
                )
            }
            .buttonStyle(HomeCardButtonStyle())
        }
    }

    @ViewBuilder
    private func toolSection(title: LocalizedStringKey, modules: [ToolModule], showsDate: Bool = false) -> some View {
        if modules.isEmpty == false {
            VStack(alignment: .leading, spacing: HomeStyle.cardSpacing) {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        sectionHeading(title)
                            .fixedSize(horizontal: true, vertical: false)

                        Spacer(minLength: 8)

                        if showsDate {
                            sectionDate
                                .fixedSize(horizontal: true, vertical: false)
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        sectionHeading(title)

                        if showsDate {
                            sectionDate
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 4)

                VStack(spacing: 0) {
                    ForEach(modules) { module in
                        let homeState = module.homeState(in: homeStateContext)

                        NavigationLink(value: module.route) {
                            FeatureEntryTile(
                                module: module,
                                isActive: homeState.isActive,
                                status: homeState.status
                            )
                        }
                        .buttonStyle(HomeCardButtonStyle())

                        if module.id != modules.last?.id {
                            Divider()
                                .padding(.leading, HomeStyle.rowContentInset)
                                .padding(.trailing, HomeStyle.rowPadding)
                        }
                    }
                }
                .homeCard()
            }
        }
    }

    private func sectionHeading(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .appSectionHeaderStyle()
    }

    private var sectionDate: some View {
        Text(currentDate, format: .dateTime.month(.abbreviated).day().weekday(.abbreviated).locale(locale))
            .font(AppDesign.metadataFont)
            .foregroundStyle(AppPalette.secondaryInk)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label(AppLocalization.string("未显示功能入口"), systemImage: "square.grid.2x2")
                .foregroundStyle(AppPalette.ink)
        } description: {
            Text(AppLocalization.string("可在设置中开启"))
                .foregroundStyle(AppPalette.secondaryInk)
        } actions: {
            Button(AppLocalization.string("打开设置"), systemImage: "slider.horizontal.3", action: openSettings)
                .buttonStyle(.borderedProminent)
                .tint(AppPalette.accent)
                .foregroundStyle(AppPalette.accentForeground)
        }
        .frame(maxWidth: .infinity, minHeight: 280)
        .homeCard()
    }

    private var visibleTools: [ToolRoute] {
        container.featureVisibilitySettings.visibleTools
    }

    private var visibleModules: [ToolModule] {
        let preferredRoutes = Self.recordingRoutes + [.reminders, .anniversaries]
        let remainingRoutes = visibleTools.filter { preferredRoutes.contains($0) == false }

        return (preferredRoutes + remainingRoutes)
            .filter { visibleTools.contains($0) }
            .map(ToolModule.init)
    }

    private var recordingModules: [ToolModule] {
        visibleModules.filter { Self.recordingRoutes.contains($0.route) }
    }

    private var utilityModules: [ToolModule] {
        visibleModules.filter { Self.recordingRoutes.contains($0.route) == false }
    }

    private var locationTracker: LocationTrackerViewModel {
        container.locationTrackerViewModel
    }

    private var isLocationActive: Bool {
        locationTracker.isRecording || locationTracker.isPaused
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
            isLocationPaused: locationTracker.isPaused,
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
