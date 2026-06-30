import SwiftUI

struct RemindersView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: RemindersViewModel?
    @Namespace private var reminderImageTransition

    var body: some View {
        Group {
            if let viewModel {
                RemindersContent(
                    viewModel: viewModel,
                    reminderImageTransition: reminderImageTransition
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("打卡任务")
        .navigationDestination(for: ReminderRoute.self) { route in
            if let viewModel {
                ReminderDetailView(
                    viewModel: viewModel,
                    reminderID: route.id,
                    reminderImageTransition: imageTransition(for: route)
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
                if viewModel.upcomingReminders.isEmpty == false {
                    Section("进行中") {
                        ForEach(viewModel.upcomingReminders) { reminder in
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
                                    .tint(.orange)
                                } else if reminder.canCheckIn() {
                                    Button {
                                        completeReminder(reminder)
                                    } label: {
                                        Label("打卡", systemImage: "checkmark.circle")
                                    }
                                    .tint(.green)
                                } else if let missedDate = reminder.firstMissedCheckInDate() {
                                    Button {
                                        makeUpRequest = ReminderMakeUpRequest(reminder: reminder, date: missedDate)
                                    } label: {
                                        Label("补签", systemImage: "calendar.badge.plus")
                                    }
                                    .tint(.orange)
                                }

                                Button {
                                    requestDeleteReminder(reminder)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        }
                    }
                }

                if viewModel.pastReminders.isEmpty == false {
                    Section("已完成") {
                        ForEach(viewModel.pastReminders) { reminder in
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
                                    .tint(.orange)
                                } else if reminder.canRestoreCompletion {
                                    Button {
                                        reopenReminder(reminder)
                                    } label: {
                                        Label("恢复", systemImage: "arrow.uturn.backward")
                                    }
                                    .tint(.blue)
                                }

                                Button {
                                    requestDeleteReminder(reminder)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .toolbar {
            Button("添加打卡任务", systemImage: "plus", action: showAddReminder)
        }
        .sheet(isPresented: $isShowingAddReminder) {
            NavigationStack {
                ReminderFormView(reminder: nil, viewModel: viewModel)
            }
        }
        .sheet(item: $makeUpRequest) { request in
            NavigationStack {
                ReminderMakeUpSheet(request: request, viewModel: viewModel)
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

    private func reopenReminder(_ reminder: ReminderRecord) {
        Task {
            await viewModel.setCompleted(reminder, isCompleted: false)
        }
    }

    private func undoTodayCheckIn(_ reminder: ReminderRecord) {
        Task {
            await viewModel.undoTodayCheckIn(reminder)
        }
    }
}

#Preview {
    NavigationStack {
        RemindersView()
            .environment(AppContainer.preview)
    }
}
