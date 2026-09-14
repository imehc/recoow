import SwiftUI

struct RemindersView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: RemindersViewModel?
    @Namespace private var reminderImageTransition

    var body: some View {
        Group {
            if let viewModel, viewModel.hasLoadedReminders {
                RemindersContent(
                    viewModel: viewModel,
                    reminderImageTransition: reminderImageTransition
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("打卡任务")
        .appNavigationStyle()
        .navigationDestination(for: ReminderRoute.self) { route in
            if let viewModel {
                ReminderDetailView(
                    viewModel: viewModel,
                    reminderID: route.id,
                    reminderImageTransition: imageTransition(for: route)
                )
            }
        }
        .navigationDestination(for: RemindersHistoryRoute.self) { _ in
            if let viewModel {
                RemindersHistoryView(
                    viewModel: viewModel,
                    reminderImageTransition: reminderImageTransition
                )
            }
        }
        .task {
            guard viewModel == nil else { return }

            let model = container.makeRemindersViewModel()
            model.startObserving()
            viewModel = model
        }
    }

    private func imageTransition(for route: ReminderRoute) -> Namespace.ID? {
        guard viewModel?.reminders.contains(where: { reminder in
            reminder.id == route.id && reminder.hasImage
        }) == true else {
            return nil
        }

        return reminderImageTransition
    }
}

private struct RemindersContent: View {
    @Bindable var viewModel: RemindersViewModel
    @State private var isShowingAddReminder = false
    @State private var reminderPendingDeletion: ReminderRecord?
    @State private var makeUpRequest: ReminderMakeUpRequest?
    @State private var earlyEndRequest: ReminderEarlyEndRequest?
    @State private var pauseRequest: ReminderPauseRequest?

    let reminderImageTransition: Namespace.ID

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            if let notificationMessage = viewModel.notificationMessage {
                Section {
                    Label(notificationMessage, systemImage: "bell.slash")
                        .foregroundStyle(.orange)
                }
            }

            if viewModel.reminders.isEmpty {
                ContentUnavailableView {
                    Label("暂无打卡任务", systemImage: "checkmark.circle")
                } description: {
                    Text("添加连续挑战、坚持目标或定时打卡")
                } actions: {
                    Button("添加打卡任务", systemImage: "plus", action: showAddReminder)
                }
            } else {
                if viewModel.activeReminders.isEmpty == false {
                    Section("进行中") {
                        ForEach(viewModel.activeReminders) { reminder in
                            activeRow(reminder)
                        }
                    }
                }

                if viewModel.pausedReminders.isEmpty == false {
                    Section("已暂停") {
                        ForEach(viewModel.pausedReminders) { reminder in
                            pausedRow(reminder)
                        }
                    }
                }
            }
        }
        .appGroupedListStyle()
        .sensoryFeedback(.success, trigger: viewModel.checkInSuccessTick)
        // 顺序只在列表出现时归位：首次进入、以及从详情/历史页返回。重排发生在转场之下，用户看不到跳动，
        // 而每次看到列表时待打卡的任务都在最上面。打卡当下那一行原地变状态，不产生位移。
        .onAppear {
            viewModel.settleActiveOrder()
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                NavigationLink(value: RemindersHistoryRoute()) {
                    Label(AppLocalization.string("历史"), systemImage: "clock.arrow.circlepath")
                }

                Button("添加打卡任务", systemImage: "plus", action: showAddReminder)
            }
        }
        .appSheet(isPresented: $isShowingAddReminder) {
            NavigationStack {
                ReminderFormView(reminder: nil, viewModel: viewModel)
            }
        }
        .appSheet(item: $makeUpRequest) { request in
            NavigationStack {
                ReminderMakeUpSheet(request: request, viewModel: viewModel)
            }
        }
        .appSheet(item: $earlyEndRequest) { request in
            NavigationStack {
                ReminderEarlyEndSheet(request: request, viewModel: viewModel)
            }
        }
        .appSheet(item: $pauseRequest) { request in
            NavigationStack {
                ReminderPauseSheet(request: request, viewModel: viewModel)
            }
        }
        .alert(
            reminderPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($reminderPendingDeletion),
            presenting: reminderPendingDeletion
        ) { reminder in
            Button("删除", role: .destructive) {
                confirmDeleteReminder(reminder)
            }
            Button("取消", role: .cancel) {
                reminderPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    @ViewBuilder
    private func activeRow(_ reminder: ReminderRecord) -> some View {
        NavigationLink(value: ReminderRoute(id: reminder.id)) {
            ReminderRow(
                reminder: reminder,
                reminderImageTransition: reminderImageTransition
            )
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if reminder.isTodayCompleted {
                Button {
                    undoTodayCheckIn(reminder)
                } label: {
                    Label("撤销打卡", systemImage: "arrow.uturn.backward.circle")
                }
                .tint(AppPalette.amber)
            } else if reminder.canCheckIn() {
                Button {
                    completeReminder(reminder)
                } label: {
                    Label("打卡", systemImage: "checkmark.circle")
                }
                .tint(AppPalette.green)
            } else if let missedDate = reminder.firstMissedCheckInDate() {
                Button {
                    makeUpRequest = ReminderMakeUpRequest(reminder: reminder, date: missedDate)
                } label: {
                    Label("补签", systemImage: "calendar.badge.plus")
                }
                .tint(AppPalette.amber)
            }

            if reminder.canPause {
                Button {
                    pauseRequest = ReminderPauseRequest(reminder: reminder)
                } label: {
                    Label("暂停", systemImage: "pause.circle")
                }
                .tint(AppPalette.amber)
            } else if reminder.canResume {
                Button {
                    resumeReminder(reminder)
                } label: {
                    Label("继续", systemImage: "play.circle")
                }
                .tint(AppPalette.green)
            }

            if reminder.canEndEarly {
                Button {
                    earlyEndRequest = ReminderEarlyEndRequest(reminder: reminder)
                } label: {
                    Label("提前结束", systemImage: "stop.circle")
                }
                .tint(AppPalette.amber)
            }

            Button {
                requestDeleteReminder(reminder)
            } label: {
                Label("删除", systemImage: "trash")
            }
            .tint(.red)
        }
    }

    @ViewBuilder
    private func pausedRow(_ reminder: ReminderRecord) -> some View {
        NavigationLink(value: ReminderRoute(id: reminder.id)) {
            ReminderRow(
                reminder: reminder,
                reminderImageTransition: reminderImageTransition
            )
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if reminder.canResume {
                Button {
                    resumeReminder(reminder)
                } label: {
                    Label("继续", systemImage: "play.circle")
                }
                .tint(AppPalette.green)
            }

            if reminder.canEndEarly {
                Button {
                    earlyEndRequest = ReminderEarlyEndRequest(reminder: reminder)
                } label: {
                    Label("提前结束", systemImage: "stop.circle")
                }
                .tint(AppPalette.amber)
            }

            Button {
                requestDeleteReminder(reminder)
            } label: {
                Label("删除", systemImage: "trash")
            }
            .tint(.red)
        }
    }

    private func showAddReminder() {
        isShowingAddReminder = true
    }

    private func requestDeleteReminder(_ reminder: ReminderRecord) {
        reminderPendingDeletion = reminder
    }

    private func confirmDeleteReminder(_ reminder: ReminderRecord) {
        reminderPendingDeletion = nil

        Task {
            await viewModel.deleteReminder(id: reminder.id)
        }
    }

    private func completeReminder(_ reminder: ReminderRecord) {
        Task {
            await viewModel.setCompleted(reminder, isCompleted: true)
        }
    }

    private func undoTodayCheckIn(_ reminder: ReminderRecord) {
        Task {
            await viewModel.undoTodayCheckIn(reminder)
        }
    }

    private func resumeReminder(_ reminder: ReminderRecord) {
        Task {
            await viewModel.resume(reminder)
        }
    }
}

#Preview {
    NavigationStack {
        RemindersView()
            .environment(AppContainer.preview)
    }
}
