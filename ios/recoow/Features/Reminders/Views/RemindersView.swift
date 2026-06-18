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
        .navigationTitle("提提醒")
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

            let model = RemindersViewModel(
                repository: container.reminderRepository,
                notificationService: container.reminderNotificationService,
                syncEngine: container.syncEngine
            )
            model.startObserving()
            viewModel = model
        }
    }

    private func imageTransition(for route: ReminderRoute) -> Namespace.ID? {
        guard viewModel?.reminders.contains(where: { reminder in
            reminder.id == route.id && reminder.imageData != nil
        }) == true else {
            return nil
        }

        return reminderImageTransition
    }
}

private struct RemindersContent: View {
    @Bindable var viewModel: RemindersViewModel
    @State private var isShowingAddReminder = false

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
                    Label("暂无提醒", systemImage: "bell")
                } description: {
                    Text("添加一个指定时间提醒")
                } actions: {
                    Button("添加提醒", systemImage: "plus", action: showAddReminder)
                }
            } else {
                if viewModel.upcomingReminders.isEmpty == false {
                    Section("即将提醒") {
                        ForEach(viewModel.upcomingReminders) { reminder in
                            NavigationLink(value: ReminderRoute(id: reminder.id)) {
                                ReminderRow(
                                    reminder: reminder,
                                    reminderImageTransition: reminderImageTransition
                                )
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    deleteReminder(reminder)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        }
                    }
                }

                if viewModel.pastReminders.isEmpty == false {
                    Section("历史记录") {
                        ForEach(viewModel.pastReminders) { reminder in
                            NavigationLink(value: ReminderRoute(id: reminder.id)) {
                                ReminderRow(
                                    reminder: reminder,
                                    reminderImageTransition: reminderImageTransition
                                )
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    deleteReminder(reminder)
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
            Button("添加提醒", systemImage: "plus", action: showAddReminder)
        }
        .sheet(isPresented: $isShowingAddReminder) {
            NavigationStack {
                ReminderFormView(reminder: nil, viewModel: viewModel)
            }
        }
    }

    private func showAddReminder() {
        isShowingAddReminder = true
    }

    private func deleteReminder(_ reminder: ReminderRecord) {
        Task {
            await viewModel.deleteReminder(id: reminder.id)
        }
    }
}

#Preview {
    NavigationStack {
        RemindersView()
            .environment(AppContainer.preview)
    }
}
